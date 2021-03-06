/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.shell;

import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Export a file so that it can be easily referenced by other {@link com.facebook.buck.rules.BuildRule}s. There are several
 * valid ways of using export_file (all examples in a build file located at "path/to/buck/BUCK").
 * The most common usage of export_file is:
 * <pre>
 *   export_file(name = 'some-file.html')
 * </pre>
 * This is equivalent to:
 * <pre>
 *   export_file(name = 'some-file.html',
 *     src = 'some-file.html',
 *     out = 'some-file.html')
 * </pre>
 * This results in "//path/to/buck:some-file.html" as the rule, and will export the file
 * "some-file.html" as "some-file.html".
 * <pre>
 *   export_file(
 *     name = 'foobar.html',
 *     src = 'some-file.html',
 *   )
 * </pre>
 * Is equivalent to:
 * <pre>
 *    export_file(name = 'foobar.html', src = 'some-file.html', out = 'foobar.html')
 * </pre>
 * Finally, it's possible to refer to the exported file with a logical name, while controlling the
 * actual file name. For example:
 * <pre>
 *   export_file(name = 'ie-exports',
 *     src = 'some-file.js',
 *     out = 'some-file-ie.js',
 *   )
 * </pre>
 * As a rule of thumb, if the "out" parameter is missing, the "name" parameter is used as the name
 * of the file to be saved.
 */
// TODO(simons): Extend to also allow exporting a rule.
public class ExportFile extends AbstractBuildable {

  private final String src;
  private final Supplier<String> out;


  @VisibleForTesting
  ExportFile(final BuildRuleParams params, Optional<String> src, Optional<String> out) {
    String shortName = params.getBuildTarget().getShortName();

    this.src = src.or(shortName);

    final String outName = out.or(shortName);

    this.out = Suppliers.memoize(new Supplier<String>() {
      @Override
      public String get() {
        String name = new File(outName).getName();
        return String.format("%s/%s%s",
            BuckConstant.GEN_DIR,
            params.getBuildTarget().getBasePathWithSlash(),
            name);
      }
    });
  }

  @Override
  public Iterable<String> getInputsToCompareToOutput() {
    return ImmutableSet.of(src);
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException {
    String pathToOutputFile = out.get();

    // This file is copied rather than symlinked so that when it is included in an archive zip and
    // unpacked on another machine, it is an ordinary file in both scenarios.
    ImmutableList.Builder<Step> builder = ImmutableList.<Step>builder()
        .add(new MkdirStep(new File(pathToOutputFile).getParent()))
        .add(new CopyStep(src, pathToOutputFile));

    return builder.build();
  }

  @Nullable
  @Override
  public String getPathToOutputFile() {
    return out.get();
  }

  public static Builder newExportFileBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildable.Builder {
    private Optional<String> src;
    private Optional<String> out;

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    public Builder setSrc(Optional<String> src) {
      this.src = src;
      return this;
    }

    public Builder setOut(Optional<String> out) {
      this.out = out;
      return this;
    }

    @Override
    protected BuildRuleType getType() {
      return BuildRuleType.EXPORT_FILE;
    }

    @Override
    protected ExportFile newBuildable(BuildRuleParams params, BuildRuleResolver resolver) {
      return new ExportFile(params, src, out);
    }
  }
}
