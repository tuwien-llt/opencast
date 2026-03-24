/*
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */
package org.opencastproject.workflow.handler.speechtotext;

import org.opencastproject.inspection.api.MediaInspectionService;
import org.opencastproject.job.api.JobContext;
import org.opencastproject.mediapackage.MediaPackage;
import org.opencastproject.mediapackage.MediaPackageElement;
import org.opencastproject.serviceregistry.api.ServiceRegistry;
import org.opencastproject.workflow.api.ConfiguredTagsAndFlavors;
import org.opencastproject.workflow.api.WorkflowInstance;
import org.opencastproject.workflow.api.WorkflowOperationException;
import org.opencastproject.workflow.api.WorkflowOperationHandler;
import org.opencastproject.workflow.api.WorkflowOperationResult;
import org.opencastproject.workspace.api.Workspace;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Workflow operation for attaching results from asynchronously running speech-to-text service jobs.
 */
@Component(
    immediate = true,
    service = WorkflowOperationHandler.class,
    property = {
        "service.description=Speech-to-Text Attach Workflow Operation Handler",
        "workflow.operation=speechtotext-attach"
    }
)
public class SpeechToTextAttachWorkflowOperationHandler extends AbstractSpeechToTextAttachOperationHandler {

  private static final Logger logger = LoggerFactory.getLogger(SpeechToTextAttachWorkflowOperationHandler.class);


  /** Workflow configuration name to store jobs in */
  private static final String JOBS_WORKFLOW_CONFIGURATION = "speech-to-text-jobs";

  @Override
  @Activate
  public void activate(ComponentContext cc) {
    super.activate(cc);
    logger.info("Registering speechtotext-attach workflow operation handler");
  }

  /**
   * {@inheritDoc}
   *
   * @see
   * WorkflowOperationHandler#start(WorkflowInstance,
   * JobContext)
   */
  @Override
  public WorkflowOperationResult start(WorkflowInstance workflowInstance, JobContext context)
          throws WorkflowOperationException {

    MediaPackage mediaPackage = workflowInstance.getMediaPackage();
    logger.info("Start speechtotext-attach workflow operation for media package {}", mediaPackage);


    // How to save the subtitle file? (as attachment, as track...)
    MediaPackageElement.Type appendSubtitleAs = getMediaPackageElementType(workflowInstance);

    // get previously started speech-to-text jobs
    var jobIds = Objects.toString(workflowInstance.getConfiguration(JOBS_WORKFLOW_CONFIGURATION), "");
    if (jobIds.isEmpty()) {
      logger.info("No speechtotext jobs to attach. Skipping.");
      return createResult(mediaPackage, WorkflowOperationResult.Action.SKIP);
    }

    int attachedSubtitles = 0;
    for (var jobId: jobIds.split(",")) {
      ConfiguredTagsAndFlavors tagsAndFlavors = getTagsAndFlavors(workflowInstance,
          Configuration.none, Configuration.none,
          Configuration.many, Configuration.one);

      boolean subtitleAttached = attachSubtitle(Long.parseLong(jobId), mediaPackage, tagsAndFlavors, appendSubtitleAs);

      if (subtitleAttached) {
        attachedSubtitles++;
      }
    }
    // Remove tracked jobs from workflow
    workflowInstance.getConfigurations().remove(JOBS_WORKFLOW_CONFIGURATION);

    if (attachedSubtitles == 0) {
      logger.info("No speech-to-text attachment, skipping.");
      return createResult(mediaPackage, WorkflowOperationResult.Action.SKIP);
    }

    logger.info("Speech-To-Text workflow operation for media package {} completed", mediaPackage);
    return createResult(mediaPackage, WorkflowOperationResult.Action.CONTINUE);
  }

  //================================================================================
  // OSGi setter
  //================================================================================

  @Reference
  public void setMediaInspectionService(MediaInspectionService mediaInspectionService) {
    this.mediaInspectionService = mediaInspectionService;
  }

  @Reference
  public void setWorkspace(Workspace workspace) {
    this.workspace = workspace;
  }

  @Reference
  public void setServiceRegistry(ServiceRegistry serviceRegistry) {
    this.serviceRegistry = serviceRegistry;
  }
}
