package com.soebes.maven.extensions.incremental;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import com.google.common.io.Files;

import org.apache.commons.lang.StringUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.internal.LifecycleModuleBuilder;
import org.apache.maven.lifecycle.internal.ProjectBuildList;
import org.apache.maven.lifecycle.internal.ReactorBuildStatus;
import org.apache.maven.lifecycle.internal.ReactorContext;
import org.apache.maven.lifecycle.internal.TaskSegment;
import org.apache.maven.lifecycle.internal.builder.Builder;
import org.apache.maven.project.MavenProject;
import org.apache.maven.scm.ChangeFile;
import org.apache.maven.scm.ChangeSet;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFile;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.ScmRevision;
import org.apache.maven.scm.command.changelog.ChangeLogScmRequest;
import org.apache.maven.scm.command.changelog.ChangeLogSet;
import org.apache.maven.scm.manager.NoSuchScmProviderException;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.repository.ScmRepository;
import org.apache.maven.scm.repository.ScmRepositoryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Incremental Module Builder behaviour.
 *
 * @author Karl Heinz Marbaise <khmarbaise@apache.org>
 */
@Singleton
@Named("incremental")
public class IncrementalModuleBuilder
    implements Builder {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    private final LifecycleModuleBuilder lifecycleModuleBuilder;

    @Inject
    private ScmManager scmManager;

    @Inject
    public IncrementalModuleBuilder(LifecycleModuleBuilder lifecycleModuleBuilder) {
        LOGGER.info(" ------------------------------------");
        LOGGER.info(" Maven Incremental Module Builder");
        LOGGER.info(" Version: {}", IncrementalModuleBuilderVersion.getVersion());
        LOGGER.debug("     SHA: {}", IncrementalModuleBuilderVersion.getRevision());
        LOGGER.info(" ------------------------------------");
        this.lifecycleModuleBuilder = lifecycleModuleBuilder;
    }

    private boolean havingScmDeveloperConnection(MavenSession session) {
        if (session.getTopLevelProject().getScm() == null) {
            LOGGER.error("The incremental module builder needs a correct scm configuration.");
            return false;
        }

        if (StringUtils.isEmpty(session.getTopLevelProject().getScm().getDeveloperConnection())) {
            LOGGER.error("The incremental module builder needs the scm developerConnection to work properly.");
            return false;
        }

        return true;
    }

    @Override
    public void build(final MavenSession session, final ReactorContext reactorContext, ProjectBuildList projectBuilds,
                      final List<TaskSegment> taskSegments, ReactorBuildStatus reactorBuildStatus)
        throws ExecutionException, InterruptedException {

        // Think about this?
        if (!session.getCurrentProject().isExecutionRoot()) {
            LOGGER.info("Not executing in root.");
        }

        Path projectRootpath = session.getTopLevelProject().getBasedir().toPath();

        if (!havingScmDeveloperConnection(session)) {
            LOGGER.warn("There is no scm developer connection configured.");
            LOGGER.warn("So we can't estimate which modules have changed.");
            return;
        }

        // TODO: Make more separation of concerns..(Extract the SCM Code from
        // here?
        ScmRepository repository = null;
        try {
            // Assumption: top level project contains the SCM entry.
            repository = scmManager.makeScmRepository(session.getTopLevelProject().getScm().getDeveloperConnection());
        } catch (ScmRepositoryException | NoSuchScmProviderException e) {
            LOGGER.error("Failure during makeScmRepository", e);
            return;
        }

        List<ScmFile> changedFiles = getChangedFiles(repository, session);
        if (changedFiles.isEmpty()) {
            LOGGER.info(" Nothing has been changed.");
        } else {

            for (ScmFile scmFile : changedFiles) {
                LOGGER.info(" Changed file: " + scmFile.getPath() + " " + scmFile.getStatus());
            }

            ModuleCalculator mc =
                new ModuleCalculator(session.getProjectDependencyGraph().getSortedProjects(), changedFiles);
            List<MavenProject> calculateChangedModules = mc.calculateChangedModules(projectRootpath);

            for (MavenProject mavenProject : calculateChangedModules) {
                LOGGER.info("Changed Project: " + mavenProject.getId());
            }

            IncrementalModuleBuilderImpl incrementalModuleBuilderImpl =
                new IncrementalModuleBuilderImpl(calculateChangedModules, lifecycleModuleBuilder, session,
                                                 reactorContext, taskSegments);

            // Really build only changed modules.
            incrementalModuleBuilderImpl.build();
        }
    }

    private List<ScmFile> getChangedFiles(ScmRepository repository, MavenSession session) {
        Set<ScmFile> changedFiles = new HashSet<>();
        File basedir = session.getTopLevelProject().getBasedir();
        ScmFileSet scmFileSet = new ScmFileSet(basedir);

        // Add from status
//        try {
//            StatusScmResult statusResult = scmManager.status(repository, scmFileSet);
//            changedFiles.addAll(statusResult.getChangedFiles());
//        } catch (ScmException e) {
//            LOGGER.error("Failure during status", e);
//        }

        // Find the SCM path for the Basedir
        String repositoryUri = repository.getProviderRepository().toString();

        // Add from changeLog (if file with starting revision found)
        String startRev = null;
        try {
            startRev =
                Files.readFirstLine(basedir.toPath().resolve("revision.txt").toFile(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Failure during revision retrieval", e);
        }
        if (startRev != null && !startRev.isEmpty()) {
            ChangeLogScmRequest changeLogScmRequest = new ChangeLogScmRequest(repository, scmFileSet);
            try {
                // as the log search is left-side inclusive, bump the revision number
                changeLogScmRequest.setStartRevision(new ScmRevision(Integer.toString(Integer.parseInt(startRev) + 1)));

                ChangeLogSet changeLogSet = scmManager.changeLog(changeLogScmRequest).getChangeLog();
                if (changeLogSet != null) {
                    for (ChangeSet changeSet : changeLogSet.getChangeSets()) {
                        addChangeFiles(repositoryUri, changeSet.getFiles(), changedFiles);
                    }
                }
            } catch (ScmException e) {
                LOGGER.error("Failure during status", e);
            }
        }

        // TODO: Change to Set everywhere?
        return new ArrayList<>(changedFiles);
    }

    private void addChangeFiles(String repositoryUri, List<ChangeFile> changeFiles, Set<ScmFile> scmFiles) {
        for (ChangeFile changeFile : changeFiles) {
            scmFiles.add(new ScmFile(relativizeRepositoryPathToBuildRoot(repositoryUri, changeFile.getName()),
                    changeFile.getAction()));
            String originalName = changeFile.getOriginalName();
            if (originalName != null) {
                scmFiles.add(new ScmFile(relativizeRepositoryPathToBuildRoot(repositoryUri, originalName),
                        changeFile.getAction()));
            }
        }
    }

    private String relativizeRepositoryPathToBuildRoot(String repositoryUri, String toRelativize) {
        for (int i = 0; i < repositoryUri.length(); i++) {
            String repositoryUriEnding = repositoryUri.substring(i);
            if (toRelativize.startsWith(repositoryUriEnding)) {
                String nameRelativized = toRelativize.substring(repositoryUriEnding.length());
                if (nameRelativized.startsWith("/") || nameRelativized.startsWith("\\")) {
                    return nameRelativized.substring(1);
                } else {
                    return nameRelativized;
                }
            }
        }
        return toRelativize;
    }

}
