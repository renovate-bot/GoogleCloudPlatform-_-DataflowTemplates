/*
 * Copyright (C) 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.plugin.maven;

import static com.google.cloud.teleport.metadata.util.MetadataUtils.bucketNameOnly;
import static com.google.cloud.teleport.plugin.DockerfileGenerator.BASE_CONTAINER_IMAGE;
import static com.google.cloud.teleport.plugin.DockerfileGenerator.BASE_PYTHON_CONTAINER_IMAGE;
import static com.google.cloud.teleport.plugin.DockerfileGenerator.JAVA_LAUNCHER_ENTRYPOINT;
import static com.google.cloud.teleport.plugin.DockerfileGenerator.PYTHON_LAUNCHER_ENTRYPOINT;
import static com.google.cloud.teleport.plugin.DockerfileGenerator.PYTHON_VERSION;

import com.google.api.gax.rpc.InvalidArgumentException;
import com.google.cloud.teleport.plugin.TemplateDefinitionsParser;
import com.google.cloud.teleport.plugin.model.ImageSpec;
import com.google.cloud.teleport.plugin.model.TemplateDefinitions;
import com.google.dataflow.v1beta3.FlexTemplatesServiceClient;
import com.google.dataflow.v1beta3.Job;
import com.google.dataflow.v1beta3.LaunchFlexTemplateParameter;
import com.google.dataflow.v1beta3.LaunchFlexTemplateRequest;
import com.google.dataflow.v1beta3.LaunchFlexTemplateResponse;
import com.google.dataflow.v1beta3.LaunchTemplateParameters;
import com.google.dataflow.v1beta3.LaunchTemplateRequest;
import com.google.dataflow.v1beta3.LaunchTemplateResponse;
import com.google.dataflow.v1beta3.TemplatesServiceClient;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URLClassLoader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringTokenizer;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Goal which stages and runs a specific Template. */
@Mojo(
    name = "run",
    defaultPhase = LifecyclePhase.PACKAGE,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class TemplatesRunMojo extends TemplatesBaseMojo {

  private static final Logger LOG = LoggerFactory.getLogger(TemplatesRunMojo.class);

  @Parameter(defaultValue = "${projectId}", readonly = true, required = true)
  protected String projectId;

  @Parameter(defaultValue = "${jobName}", readonly = true, required = false)
  protected String jobName;

  @Parameter(defaultValue = "${templateName}", readonly = true, required = false)
  protected String templateName;

  @Parameter(defaultValue = "${bucketName}", readonly = true, required = true)
  protected String bucketName;

  @Parameter(defaultValue = "${stagePrefix}", readonly = true, required = false)
  protected String stagePrefix;

  @Parameter(defaultValue = "${region}", readonly = true, required = false)
  protected String region;

  @Parameter(defaultValue = "${artifactRegion}", readonly = true, required = false)
  protected String artifactRegion;

  /**
   * Artifact registry.
   *
   * <p>If not set, images will be built to [artifactRegion.]gcr.io/[projectId].
   *
   * <p>If set to "xxx.gcr.io", image will be built to xxx.gcr.io/[projectId].
   *
   * <p>Otherwise, image will be built to artifactRegion.
   */
  @Parameter(defaultValue = "${artifactRegistry}", readonly = true, required = false)
  protected String artifactRegistry;

  /**
   * Staging artifact registry.
   *
   * <p>If set, images will first build inside stagingArtifactRegistry before promote to final
   * destination. Only effective when generateSBOM.
   */
  @Parameter(defaultValue = "${stagingArtifactRegistry}", readonly = true, required = false)
  protected String stagingArtifactRegistry;

  @Parameter(defaultValue = "${gcpTempLocation}", readonly = true, required = false)
  protected String gcpTempLocation;

  @Parameter(
      defaultValue = BASE_CONTAINER_IMAGE,
      property = "baseContainerImage",
      readonly = true,
      required = false)
  protected String baseContainerImage;

  @Parameter(
      defaultValue = BASE_PYTHON_CONTAINER_IMAGE,
      property = "basePythonContainerImage",
      readonly = true,
      required = false)
  protected String basePythonContainerImage;

  @Parameter(
      defaultValue = PYTHON_LAUNCHER_ENTRYPOINT,
      property = "pythonTemplateLauncherEntryPoint",
      readonly = true,
      required = false)
  protected String pythonTemplateLauncherEntryPoint;

  @Parameter(
      defaultValue = JAVA_LAUNCHER_ENTRYPOINT,
      property = "javaTemplateLauncherEntryPoint",
      readonly = true,
      required = false)
  protected String javaTemplateLauncherEntryPoint;

  @Parameter(
      defaultValue = PYTHON_VERSION,
      property = "pythonVersion",
      readonly = true,
      required = false)
  protected String pythonVersion;

  @Parameter(defaultValue = "${beamVersion}", readonly = true, required = false)
  protected String beamVersion;

  @Parameter(defaultValue = "${unifiedWorker}", readonly = true, required = false)
  protected boolean unifiedWorker;

  @Parameter(defaultValue = "${parameters}", readonly = true, required = true)
  protected String parameters;

  @Parameter(defaultValue = "false", property = "generateSBOM", readonly = true, required = false)
  protected boolean generateSBOM;

  public void execute() throws MojoExecutionException {

    try {
      URLClassLoader loader = buildClassloader();

      BuildPluginManager pluginManager =
          (BuildPluginManager) session.lookup("org.apache.maven.plugin.BuildPluginManager");

      LOG.info("Staging Templates to bucket '{}'...", bucketNameOnly(bucketName));

      List<TemplateDefinitions> templateDefinitions =
          TemplateDefinitionsParser.scanDefinitions(loader, outputDirectory);

      Optional<TemplateDefinitions> definitionsOptional =
          templateDefinitions.stream()
              .filter(candidate -> candidate.getTemplateAnnotation().name().equals(templateName))
              .findFirst();

      if (definitionsOptional.isEmpty()) {
        LOG.warn("Not found template with the name " + templateName + " to run in this module.");
        return;
      }

      TemplateDefinitions definition = definitionsOptional.get();
      ImageSpec imageSpec = definition.buildSpecModel(false);
      String currentTemplateName = imageSpec.getMetadata().getName();

      if (stagePrefix == null || stagePrefix.isEmpty()) {
        stagePrefix = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date()) + "_RC01";
      }

      LOG.info("Staging template {}...", currentTemplateName);

      String useRegion = StringUtils.isNotEmpty(region) ? region : "us-central1";

      // TODO: is there a better way to get the plugin on the _same project_?
      TemplatesStageMojo configuredMojo =
          new TemplatesStageMojo(
              project,
              session,
              outputDirectory,
              outputClassesDirectory,
              resourcesDirectory,
              targetDirectory,
              projectId,
              templateName,
              bucketName,
              bucketName,
              stagePrefix,
              useRegion,
              artifactRegion,
              gcpTempLocation,
              baseContainerImage,
              basePythonContainerImage,
              pythonTemplateLauncherEntryPoint,
              javaTemplateLauncherEntryPoint,
              pythonVersion,
              beamVersion,
              artifactRegistry,
              stagingArtifactRegistry,
              unifiedWorker,
              generateSBOM);

      String useJobName =
          StringUtils.isNotEmpty(jobName)
              ? jobName
              : templateName.toLowerCase().replace('_', '-')
                  + "-"
                  + new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date());

      String stagedTemplatePath =
          configuredMojo.stageTemplate(definition, imageSpec, pluginManager);

      Job job;
      if (definition.isClassic()) {
        job = runClassicTemplate(stagedTemplatePath, useJobName, useRegion);
      } else {
        job = runFlexTemplate(stagedTemplatePath, useJobName, useRegion);
      }

      LOG.info(
          "Created template job with ID {}. Console:"
              + " https://console.cloud.google.com/dataflow/jobs/{}/{}?project={}",
          job.getId(),
          job.getLocation(),
          job.getId(),
          job.getProjectId());

      LOG.info(
          "To cancel the project, run: gcloud dataflow jobs cancel {} --project {} --region {}",
          job.getId(),
          job.getProjectId(),
          job.getLocation());

    } catch (DependencyResolutionRequiredException e) {
      throw new MojoExecutionException("Dependency resolution failed", e);
    } catch (MalformedURLException e) {
      throw new MojoExecutionException("URL generation failed", e);
    } catch (InvalidArgumentException e) {
      throw new MojoExecutionException("Invalid run argument", e);
    } catch (Exception e) {
      throw new MojoExecutionException("Template run failed", e);
    }
  }

  private Job runClassicTemplate(String templatePath, String jobName, String useRegion)
      throws MojoExecutionException, IOException, InterruptedException {
    Job job;
    String stagingPath = "gs://" + bucketNameOnly(bucketName) + "/" + stagePrefix + "/staging/";

    String[] runCmd =
        new String[] {
          "gcloud",
          "dataflow",
          "jobs",
          "run",
          jobName,
          "--gcs-location",
          templatePath,
          "--project",
          projectId,
          "--region",
          useRegion,
          "--staging-location",
          stagingPath,
          "--parameters",
          parameters
        };

    LOG.info(
        "Template {} staged! Run Classic Template command: {}",
        templateName,
        String.join(" ", runCmd));

    try (TemplatesServiceClient client = TemplatesServiceClient.create()) {
      LaunchTemplateParameters launchParameters =
          LaunchTemplateParameters.newBuilder()
              .setJobName(jobName)
              .putAllParameters(parseParameters(parameters))
              .build();
      LaunchTemplateResponse launchTemplateResponse =
          client.launchTemplate(
              LaunchTemplateRequest.newBuilder()
                  .setLaunchParameters(launchParameters)
                  .setGcsPath(templatePath)
                  .setProjectId(projectId)
                  .setLocation(useRegion)
                  .build());

      job = launchTemplateResponse.getJob();
    }
    return job;
  }

  private Job runFlexTemplate(String templatePath, String jobName, String useRegion)
      throws MojoExecutionException, IOException, InterruptedException {
    Job job;

    String[] runCmd =
        new String[] {
          "gcloud",
          "dataflow",
          "flex-template",
          "run",
          jobName,
          "--template-file-gcs-location",
          templatePath,
          "--project",
          projectId,
          "--region",
          useRegion,
          "--parameters",
          parameters
        };

    LOG.info(
        "Template {} staged! Run Flex Template command: {}",
        templateName,
        String.join(" ", runCmd));

    try (FlexTemplatesServiceClient client = FlexTemplatesServiceClient.create()) {
      LaunchFlexTemplateParameter launchParameters =
          LaunchFlexTemplateParameter.newBuilder()
              .setJobName(jobName)
              .setContainerSpecGcsPath(templatePath)
              .putAllParameters(parseParameters(parameters))
              .build();
      LaunchFlexTemplateResponse launchFlexTemplateResponse =
          client.launchFlexTemplate(
              LaunchFlexTemplateRequest.newBuilder()
                  .setLaunchParameter(launchParameters)
                  .setProjectId(projectId)
                  .setLocation(useRegion)
                  .build());

      job = launchFlexTemplateResponse.getJob();
    }
    return job;
  }

  private Map<String, String> parseParameters(String parameters) {
    Map<String, String> parametersMap = new LinkedHashMap<>();

    StringTokenizer tokenizer = new StringTokenizer(parameters, ",");
    while (tokenizer.hasMoreElements()) {
      String[] token = tokenizer.nextToken().split("=");
      parametersMap.put(token[0], token[1]);
    }
    return parametersMap;
  }
}
