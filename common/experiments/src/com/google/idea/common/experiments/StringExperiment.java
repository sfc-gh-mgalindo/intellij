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
package com.google.idea.common.experiments;

import javax.annotation.Nullable;

/** String-valued experiment. */
public class StringExperiment extends Experiment {

  public final String defaultValue;

  public StringExperiment(String key, String defaultValue) {
    super(key);
    this.defaultValue = defaultValue;
  }

  public StringExperiment(String key) {
    this(key, null);
  }

  @Nullable
  public String getValue() {
    return ExperimentService.getInstance().getExperimentString(this, null);
  }

  @Override
  public String getLogValue() {
    return String.valueOf(getValue());
  }

  @Override
  public String getRawDefault() {
    return defaultValue;
  }
}
