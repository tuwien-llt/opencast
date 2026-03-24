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
import org.opencastproject.job.api.Job;
import org.opencastproject.mediapackage.MediaPackage;
import org.opencastproject.mediapackage.MediaPackageElement;
import org.opencastproject.mediapackage.MediaPackageElementBuilderFactory;
import org.opencastproject.mediapackage.MediaPackageElementParser;
import org.opencastproject.serviceregistry.api.ServiceRegistryException;
import org.opencastproject.speechtotext.api.SpeechToTextServiceException;
import org.opencastproject.util.NotFoundException;
import org.opencastproject.workflow.api.AbstractWorkflowOperationHandler;
import org.opencastproject.workflow.api.ConfiguredTagsAndFlavors;
import org.opencastproject.workflow.api.WorkflowInstance;
import org.opencastproject.workflow.api.WorkflowOperationException;
import org.opencastproject.workflow.api.WorkflowOperationInstance;
import org.opencastproject.workspace.api.Workspace;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

public abstract class AbstractSpeechToTextAttachOperationHandler extends AbstractWorkflowOperationHandler {
  /**
   * The logging facility
   */
  private static final Logger logger = LoggerFactory.getLogger(AbstractSpeechToTextAttachOperationHandler.class);

  private static final String NO_RESULT = "NO_RESULT";

  /**
   * The workspace service.
   */
  protected Workspace workspace;

  /**
   * The inspection service.
   */
  protected MediaInspectionService mediaInspectionService;

  /**
   * Property name for configuring the place where the subtitles shall be appended.
   */
  private static final String TARGET_ELEMENT = "target-element";

  /**
   * Creates the subtitle file for a track and appends it to the media package.
   *
   * @param jobId          Identifier of the speectotext job
   * @param mediaPackage   The media package where the track is located.
   * @param tagsAndFlavors Tags and flavors instance (to get target flavor information)
   * @param subtitleType   Tells how the subtitles file has to be appended.
   * @throws WorkflowOperationException Get thrown if an error occurs.
   */
  protected boolean attachSubtitle(long jobId, MediaPackage mediaPackage, ConfiguredTagsAndFlavors tagsAndFlavors,
                                   MediaPackageElement.Type subtitleType) throws WorkflowOperationException {

    logger.info("Attaching subtitle from job '{}' to media package {}", jobId, mediaPackage);
    Job job;
    try {
      job = serviceRegistry.getJob(jobId);
    } catch (NotFoundException | ServiceRegistryException e) {
      throw new WorkflowOperationException(
          String.format("Could not find speechtotext job %s", jobId), e);
    }
    if (!"speechtotext".equals(job.getOperation())) {
      throw new WorkflowOperationException(String.format(
          "Job %s is on type %s. Expected `speechtotext`", jobId, job.getOperation()));
    }

    if (!waitForStatus(job).isSuccess()) {
      throw new WorkflowOperationException(
          String.format("Speechtotext job for media package '%s' failed", mediaPackage));
    }

    if (job.getPayload().equals(NO_RESULT)) {
      logger.info("No output from speech-to-text job {}.", job.getId());
      return false;
    }

    // add subtitle to media package
    try {
      String[] jobOutput = job.getPayload().split(",");
      URI output = new URI(jobOutput[0]);
      String outputLanguage = jobOutput[1];
      String engineType = jobOutput[2];

      var subtitleElementType = MediaPackageElementBuilderFactory.newInstance().newElementBuilder()
          .newElement(subtitleType, tagsAndFlavors.getSingleTargetFlavor());
      var elementId = subtitleElementType.generateIdentifier();

      try (InputStream in = workspace.read(output)) {
        URI uri = workspace.put(mediaPackage.getIdentifier().toString(), elementId,
            FilenameUtils.getName(output.getPath()), in);
        subtitleElementType.setURI(uri);
      }

      ConfiguredTagsAndFlavors.TargetTags targetTags = tagsAndFlavors.getTargetTags();
      targetTags.getOverrideTags().add("lang:" + outputLanguage);
      targetTags.getOverrideTags().add("generator-type:auto");
      targetTags.getOverrideTags().add("generator:" + engineType.toLowerCase());
      applyTargetTagsToElement(targetTags, subtitleElementType);

      // this is used to set some values automatically, like the correct mimetype
      Job inspection = mediaInspectionService.enrich(subtitleElementType, true);
      if (!waitForStatus(inspection).isSuccess()) {
        throw new SpeechToTextServiceException(String.format(
            "Transcription for '%s' failed at enriching process", mediaPackage));
      }

      mediaPackage.add(MediaPackageElementParser.getFromXml(inspection.getPayload()));

      workspace.delete(output);
    } catch (Exception e) {
      throw new WorkflowOperationException("Error handling text-to-speech service output", e);
    }

    try {
      workspace.cleanup(mediaPackage.getIdentifier());
      return true;
    } catch (IOException e) {
      throw new WorkflowOperationException(e);
    }
  }

  /**
   * Get the information how to append the subtitles file to the media package.
   *
   * @param workflowInstance Contains the workflow configuration.
   * @return How to append the subtitles file to the media package.
   * @throws WorkflowOperationException Get thrown if an error occurs.
   */
  protected MediaPackageElement.Type getMediaPackageElementType(WorkflowInstance workflowInstance)
            throws WorkflowOperationException {
    WorkflowOperationInstance operation = workflowInstance.getCurrentOperation();
    String targetElement = StringUtils.trimToEmpty(operation.getConfiguration(TARGET_ELEMENT)).toLowerCase();
    if (targetElement.isEmpty()) {
      return MediaPackageElement.Type.Track;
    }
    if (targetElement.equalsIgnoreCase(MediaPackageElement.Type.Track.name())) {
      return MediaPackageElement.Type.Track;
    } else if (targetElement.equalsIgnoreCase(MediaPackageElement.Type.Attachment.name())) {
      return MediaPackageElement.Type.Attachment;
    } else {
      throw new WorkflowOperationException(String.format(
          "Speech-to-Text job for media package '%s' failed, because of wrong workflow configuration. "
              + "target-element of type '%s' does not exist.", workflowInstance.getMediaPackage(), targetElement));
    }
  }
}
