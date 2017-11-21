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
package com.google.idea.blaze.base.run.targetfinder;

import com.google.idea.blaze.base.dependencies.TargetInfo;
import com.google.idea.blaze.base.model.primitives.Label;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import javax.annotation.Nullable;

/** Finds information about targets matching a given label. */
public interface TargetFinder {

  ExtensionPointName<TargetFinder> EP_NAME =
      ExtensionPointName.create("com.google.idea.blaze.TargetFinder");

  @Nullable
  static TargetInfo findTargetInfo(Project project, Label label) {
    for (TargetFinder finder : EP_NAME.getExtensions()) {
      TargetInfo target = finder.findTarget(project, label);
      if (target != null) {
        return target;
      }
    }
    return null;
  }

  /** Returns a {@link TargetInfo} corresponding to the given blaze label. */
  @Nullable
  TargetInfo findTarget(Project project, Label label);
}
