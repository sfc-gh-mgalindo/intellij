/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.clwb.run;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.idea.blaze.base.command.BlazeCommandName;
import com.google.idea.blaze.base.command.BlazeInvocationContext;
import com.google.idea.blaze.base.command.buildresult.BuildResultHelper;
import com.google.idea.blaze.base.command.buildresult.BuildResultHelper.GetArtifactsException;
import com.google.idea.blaze.base.command.buildresult.LocalFileArtifact;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.model.primitives.TargetExpression;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.run.BlazeBeforeRunCommandHelper;
import com.google.idea.blaze.base.run.BlazeCommandRunConfiguration;
import com.google.idea.blaze.base.run.ExecutorType;
import com.google.idea.blaze.base.run.confighandler.BlazeCommandRunConfigurationRunner;
import com.google.idea.blaze.base.sync.aspects.BuildResult;
import com.google.idea.blaze.base.util.SaveUtil;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.RunCanceledByUserException;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.util.PathUtil;
import com.jetbrains.cidr.execution.CidrCommandLineState;
import com.jetbrains.cidr.lang.workspace.compiler.ClangCompilerKind;

import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

/** CLion-specific handler for {@link BlazeCommandRunConfiguration}s. */
public class BlazeCidrRunConfigurationRunner implements BlazeCommandRunConfigurationRunner {
  private final BlazeCommandRunConfiguration configuration;

  /** Calculated during the before-run task, and made available to the debugger. */
  File executableToDebug = null;

  /** Calculated during the before-run task, and made available to the debugger. */
  Map<String, String> testEnvironment = null;

  BlazeCidrRunConfigurationRunner(BlazeCommandRunConfiguration configuration) {
    this.configuration = configuration;
  }

  @Override
  public RunProfileState getRunProfileState(Executor executor, ExecutionEnvironment env) {
    return new CidrCommandLineState(env, new BlazeCidrLauncher(configuration, this, env));
  }

  private static Map<String, String> parseEnvironmentFile(File env) throws IOException {
      // Parses a file that contains a list of environment variables
      // with the format "key=value\0ey=value\0" per line and returns the content of the
      // file into a map.
      ImmutableMap.Builder<String, String> builder = new ImmutableMap.Builder<>();
      try (BufferedReader reader = new BufferedReader(new FileReader(env))) {
        String line;
        while ((line = reader.readLine()) != null) {
          String[] entries = line.split("\0");
          for (String entry : entries) {
            int idx = entry.indexOf('=');
            if (idx != -1) {
              String key = entry.substring(0, idx);
              String value = entry.substring(idx + 1);
              builder.put(key, value);
            }
          }
        }
      }
      return builder.build();
  }

  @Override
  public boolean executeBeforeRunTask(ExecutionEnvironment env) {
    executableToDebug = null;
    if (!isDebugging(env)) {
      return true;
    }
    try {
      File executable = getExecutableToDebug(env);
      BlazeCommandName command =
        ((BlazeCidrRunConfigState) configuration.getHandler().getState()).getCommandState().getCommand();
      if (BlazeCommandName.TEST.equals(command)) {
        testEnvironment = getTestEnvironment(env);
      }
 
      if (executable != null) {
        executableToDebug = executable;
        return true;
      }
    } catch (ExecutionException e) {
      ExecutionUtil.handleExecutionError(
          env.getProject(), env.getExecutor().getToolWindowId(), env.getRunProfile(), e);
    }
    return false;
  }

  private static boolean isDebugging(ExecutionEnvironment environment) {
    Executor executor = environment.getExecutor();
    return executor instanceof DefaultDebugExecutor;
  }

  private static Label getSingleTarget(BlazeCommandRunConfiguration config)
      throws ExecutionException {
    ImmutableList<? extends TargetExpression> targets = config.getTargets();
    if (targets.size() != 1 || !(targets.get(0) instanceof Label)) {
      throw new ExecutionException("Invalid configuration: doesn't have a single target label");
    }
    return (Label) targets.get(0);
  }

  private ImmutableList<String> getExtraDebugFlags(ExecutionEnvironment env) {
    if (Registry.is("bazel.clwb.debug.extraflags.disabled")) {
      return ImmutableList.of();
    }

    final var debuggerKind = RunConfigurationUtils.getDebuggerKind(configuration);
    if (debuggerKind == BlazeDebuggerKind.GDB_SERVER) {
      return BlazeGDBServerProvider.getFlagsForDebugging(configuration.getHandler().getState());
    }

    final var flagsBuilder = ImmutableList.<String>builder();

    if (debuggerKind == BlazeDebuggerKind.BUNDLED_LLDB && !Registry.is("bazel.trim.absolute.path.disabled")) {
      flagsBuilder.add("--copt=-fdebug-compilation-dir=" + WorkspaceRoot.fromProject(env.getProject()));

      if (SystemInfo.isMac) {
        flagsBuilder.add("--linkopt=-Wl,-oso_prefix,.");
      }
    }

    flagsBuilder.add("--compilation_mode=dbg");
    flagsBuilder.add("--copt=-O0");
    flagsBuilder.add("--copt=-g");
    flagsBuilder.add("--strip=never");
    flagsBuilder.add("--dynamic_mode=off");
    flagsBuilder.addAll(BlazeGDBServerProvider.getOptionalFissionArguments());

    return flagsBuilder.build();
  }

  /**
   * Builds blaze C/C++ target in debug mode, and returns the output build artifact.
   *
   * @throws ExecutionException if no unique output artifact was found.
   */
  private File getExecutableToDebug(ExecutionEnvironment env) throws ExecutionException {
    SaveUtil.saveAllFiles();
    try (final var buildResultHelper = new BuildResultHelper()) {
      ListenableFuture<BuildResult> buildOperation =
          BlazeBeforeRunCommandHelper.runBlazeCommand(
              BlazeCommandName.BUILD,
              configuration,
              buildResultHelper,
              ImmutableList.of(),
              getExtraDebugFlags(env),
              BlazeInvocationContext.runConfigContext(
                  ExecutorType.fromExecutor(env.getExecutor()), configuration.getType(), true),
              "Building debug binary");

      Label target = getSingleTarget(configuration);
      try {
        BuildResult result = buildOperation.get();
        if (result.status != BuildResult.Status.SUCCESS) {
          throw new ExecutionException("Blaze failure building debug binary");
        }
      } catch (InterruptedException | CancellationException e) {
        buildOperation.cancel(true);
        throw new RunCanceledByUserException();
      } catch (java.util.concurrent.ExecutionException e) {
        throw new ExecutionException(e);
      }
      List<File> candidateFiles;
      try {
        candidateFiles =
            LocalFileArtifact.getLocalFiles(
                    buildResultHelper.getBuildArtifactsForTarget(target, file -> true))
                .stream()
                .filter(File::canExecute)
                .collect(Collectors.toList());
      } catch (GetArtifactsException e) {
        throw new ExecutionException(
            String.format(
                "Failed to get output artifacts when building %s: %s", target, e.getMessage()));
      }
      if (candidateFiles.isEmpty()) {
        throw new ExecutionException(
            String.format("No output artifacts found when building %s", target));
      }
      File file = findExecutable(target, candidateFiles);
      if (file == null) {
        throw new ExecutionException(
            String.format(
                "More than 1 executable was produced when building %s; don't know which to debug",
                target));
      }
      LocalFileSystem.getInstance().refreshIoFiles(ImmutableList.of(file));
      return file;
    }
  }

  /**
   * Obtains the details necessary to run a test target outside of Bazel.
   *
   * This works by creating a script that extracts environmental information into textual files
   * and making the test run via this script with {@code --run_under}.
   *
   * @throws ExecutionException if there was a Bazel execution error.
   */
  private Map<String, String> getTestEnvironment(ExecutionEnvironment env) throws ExecutionException {
    SaveUtil.saveAllFiles();

    File runUnderScript;
    File envFile;
    try {
        runUnderScript = File.createTempFile("clwb", "-test_env_collect.sh");
        runUnderScript.setExecutable(true);
        envFile = File.createTempFile("clwb", "-test_env");
        FileWriter fileWriter = new FileWriter(runUnderScript, true);
        BufferedWriter bw = new BufferedWriter(fileWriter);
        bw.write(
            String.join(
	        "\n",
                "#!/bin/sh",
                String.format("env -0 > %s", envFile.getAbsolutePath())));
        bw.close();
    } catch (IOException e) {
        throw new ExecutionException(e);
    }

    try (final var buildResultHelper = new BuildResultHelper()) {
      ListenableFuture<BuildResult> buildOperation =
          BlazeBeforeRunCommandHelper.runBlazeCommand(
              BlazeCommandName.TEST,
              configuration,
             buildResultHelper,
              ImmutableList.of(
                  "--spawn_strategy=local",
                  "--run_under=" + runUnderScript.getAbsolutePath()),
              getExtraDebugFlags(env),
              BlazeInvocationContext.runConfigContext(
                  ExecutorType.fromExecutor(env.getExecutor()), configuration.getType(), true),
              "Gathering test environment");

      try {
        BuildResult result = buildOperation.get();
        if (result.status != BuildResult.Status.SUCCESS) {
          throw new ExecutionException("Bazel failure gathering test environment");
        }
      } catch (InterruptedException | CancellationException e) {
        buildOperation.cancel(true);
        throw new RunCanceledByUserException();
      } catch (java.util.concurrent.ExecutionException e) {
        throw new ExecutionException(e);
      }
      LocalFileSystem.getInstance().refreshIoFiles(ImmutableList.of(envFile));
      try {
        return parseEnvironmentFile(envFile);
      } catch (IOException e) {
        throw new ExecutionException(e);
      }
    }
  }

  /**
   * Basic heuristic for choosing between multiple output files. Currently just looks for a filename
   * matching the target name.
   */
  @Nullable
  private static File findExecutable(Label target, List<File> outputs) {
    if (outputs.size() == 1) {
      return outputs.get(0);
    }
    String name = PathUtil.getFileName(target.targetName().toString());
    for (File file : outputs) {
      if (file.getName().equals(name)) {
        return file;
      }
    }
    return null;
  }
}
