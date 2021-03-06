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

package com.facebook.buck.cli;

import static com.facebook.buck.util.BuckConstant.GEN_DIR;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.hamcrest.Matchers.contains;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.java.DefaultJavaPackageFinder;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.java.JavaTestRule;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleSuccess;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.FakeTestRule;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.hamcrest.collection.IsIterableContainingInAnyOrder;
import org.hamcrest.collection.IsIterableContainingInOrder;
import org.junit.BeforeClass;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class TestCommandTest {

  private static ImmutableSortedSet<String> pathsFromRoot;
  private static ImmutableSet<String> pathElements;

  @BeforeClass
  public static void setUp() {
    pathsFromRoot = ImmutableSortedSet.of("java/");
    pathElements = ImmutableSet.of("src", "src-gen");
  }

  /**
   * If the source paths specified are all generated files, then our path to source tmp
   * should be absent.
   */
  @Test
  public void testGeneratedSourceFile() {
    String pathToGenFile = GEN_DIR + "/GeneratedFile.java";
    assertTrue(JavaTestRule.isGeneratedFile(pathToGenFile));

    ImmutableSortedSet<String> javaSrcs = ImmutableSortedSet.of(pathToGenFile);

    JavaLibraryRule javaLibraryRule = createMock(JavaLibraryRule.class);
    expect(javaLibraryRule.getJavaSrcs())
        .andReturn(ImmutableSortedSet.copyOf(javaSrcs));

    DefaultJavaPackageFinder defaultJavaPackageFinder =
        createMock(DefaultJavaPackageFinder.class);

    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);

    Object[] mocks = new Object[] {projectFilesystem, defaultJavaPackageFinder, javaLibraryRule};
    replay(mocks);

    ImmutableSet<String> result = TestCommand.getPathToSourceFolders(
        javaLibraryRule, Optional.of(defaultJavaPackageFinder), projectFilesystem);

    assertTrue("No path should be returned if the library contains only generated files.",
        result.isEmpty());

    verify(mocks);
  }

  /**
   * If the source paths specified are all for non-generated files then we should return
   * the correct source tmp corresponding to a non-generated source path.
   */
  @Test
  public void testNonGeneratedSourceFile() {
    String pathToNonGenFile = "package/src/SourceFile1.java";
    assertFalse(JavaTestRule.isGeneratedFile(pathToNonGenFile));

    ImmutableSortedSet<String> javaSrcs = ImmutableSortedSet.of(pathToNonGenFile);

    File parentFile = createMock(File.class);
    expect(parentFile.getName()).andReturn("src");
    expect(parentFile.getPath()).andReturn("package/src");

    File sourceFile = createMock(File.class);
    expect(sourceFile.getParentFile()).andReturn(parentFile);

    DefaultJavaPackageFinder defaultJavaPackageFinder =
        createMock(DefaultJavaPackageFinder.class);
    expect(defaultJavaPackageFinder.getPathsFromRoot()).andReturn(pathsFromRoot);
    expect(defaultJavaPackageFinder.getPathElements()).andReturn(pathElements);

    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    expect(projectFilesystem.getFileForRelativePath(pathToNonGenFile))
        .andReturn(sourceFile);

    JavaLibraryRule javaLibraryRule = createMock(JavaLibraryRule.class);
    expect(javaLibraryRule.getJavaSrcs())
        .andReturn(ImmutableSortedSet.copyOf(javaSrcs));

    Object[] mocks = new Object[] {
        parentFile,
        sourceFile,
        defaultJavaPackageFinder,
        projectFilesystem,
        javaLibraryRule};
    replay(mocks);

    ImmutableSet<String> result = TestCommand.getPathToSourceFolders(
        javaLibraryRule, Optional.of(defaultJavaPackageFinder), projectFilesystem);

    assertEquals("All non-generated source files are under one source tmp.",
        ImmutableSet.of("package/src/"), result);

    verify(mocks);
  }

  /**
   * If the source paths specified are from the new unified source tmp then we should return
   * the correct source tmp corresponding to the unified source path.
   */
  @Test
  public void testUnifiedSourceFile() {
    String pathToNonGenFile = "java/package/SourceFile1.java";
    assertFalse(JavaTestRule.isGeneratedFile(pathToNonGenFile));

    ImmutableSortedSet<String> javaSrcs = ImmutableSortedSet.of(pathToNonGenFile);

    DefaultJavaPackageFinder defaultJavaPackageFinder =
        createMock(DefaultJavaPackageFinder.class);
    expect(defaultJavaPackageFinder.getPathsFromRoot()).andReturn(pathsFromRoot);

    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);

    JavaLibraryRule javaLibraryRule = createMock(JavaLibraryRule.class);
    expect(javaLibraryRule.getJavaSrcs())
        .andReturn(ImmutableSortedSet.copyOf(javaSrcs));

    Object[] mocks = new Object[] {defaultJavaPackageFinder, projectFilesystem, javaLibraryRule};
    replay(mocks);

    ImmutableSet<String> result = TestCommand.getPathToSourceFolders(
        javaLibraryRule, Optional.of(defaultJavaPackageFinder), projectFilesystem);

    assertEquals("All non-generated source files are under one source tmp.",
        ImmutableSet.of("java/"), result);

    verify(mocks);
  }

  /**
   * If the source paths specified contains one source path to a non-generated file then
   * we should return the correct source tmp corresponding to that non-generated source path.
   * Especially when the generated file comes first in the ordered set.
   */
  @Test
  public void testMixedSourceFile() {
    String pathToGenFile = (GEN_DIR + "/com/facebook/GeneratedFile.java");
    String pathToNonGenFile1 = ("package/src/SourceFile1.java");
    String pathToNonGenFile2 = ("package/src-gen/SourceFile2.java");

    ImmutableSortedSet<String> javaSrcs = ImmutableSortedSet.of(
        pathToGenFile, pathToNonGenFile1, pathToNonGenFile2);

    File parentFile1 = createMock(File.class);
    expect(parentFile1.getName()).andReturn("src");
    expect(parentFile1.getPath()).andReturn("package/src");

    File sourceFile1 = createMock(File.class);
    expect(sourceFile1.getParentFile()).andReturn(parentFile1);

    File parentFile2 = createMock(File.class);
    expect(parentFile2.getName()).andReturn("src");
    expect(parentFile2.getPath()).andReturn("package/src-gen");

    File sourceFile2 = createMock(File.class);
    expect(sourceFile2.getParentFile()).andReturn(parentFile2);

    DefaultJavaPackageFinder defaultJavaPackageFinder =
        createMock(DefaultJavaPackageFinder.class);
    expect(defaultJavaPackageFinder.getPathsFromRoot()).andReturn(pathsFromRoot).times(2);
    expect(defaultJavaPackageFinder.getPathElements()).andReturn(pathElements).times(2);

    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    expect(projectFilesystem.getFileForRelativePath(pathToNonGenFile1))
        .andReturn(sourceFile1);
    expect(projectFilesystem.getFileForRelativePath(pathToNonGenFile2))
        .andReturn(sourceFile2);

    JavaLibraryRule javaLibraryRule = createMock(JavaLibraryRule.class);
    expect(javaLibraryRule.getJavaSrcs())
        .andReturn(ImmutableSortedSet.copyOf(javaSrcs));

    Object[] mocks = new Object[] {
        parentFile1,
        sourceFile1,
        parentFile2,
        sourceFile2,
        defaultJavaPackageFinder,
        projectFilesystem,
        javaLibraryRule};
    replay(mocks);

    ImmutableSet<String> result = TestCommand.getPathToSourceFolders(
        javaLibraryRule, Optional.of(defaultJavaPackageFinder), projectFilesystem);

    assertEquals("The non-generated source files are under two different source folders.",
        ImmutableSet.of("package/src-gen/", "package/src/"), result);

    verify(mocks);
  }

  private TestCommandOptions getOptions(String...args) throws CmdLineException {
    TestCommandOptions options = new TestCommandOptions(new FakeBuckConfig());
    new CmdLineParserAdditionalOptions(options).parseArgument(args);
    return options;
  }

  private DependencyGraph createDependencyGraphFromBuildRules(Iterable<? extends BuildRule> rules) {
    MutableDirectedGraph<BuildRule> graph = new MutableDirectedGraph<BuildRule>();
    for (BuildRule rule : rules) {
      for (BuildRule dep : rule.getDeps()) {
        graph.addEdge(rule, dep);
      }
    }

    return new DependencyGraph(graph);
  }

  /**
   * Tests the --xml flag, ensuring that test result data is correctly
   * formatted.
   */
  @Test
  public void testXmlGeneration() throws Exception {
    // Set up sample test data.
    TestResultSummary result1 = new TestResultSummary(
        /* testCaseName */ "TestCase",
        /* testName */ "passTest",
        /* isSuccess */ true,
        /* time */ 5000,
        /* message */ null,
        /* stacktrace */ null,
        /* stdOut */ null,
        /* stdErr */ null);
    TestResultSummary result2 = new TestResultSummary(
        /* testCaseName */ "TestCase",
        /* testName */ "failWithMsg",
        /* isSuccess */ false,
        /* time */ 7000,
        /* message */ "Index out of bounds!",
        /* stacktrace */ "Stacktrace",
        /* stdOut */ null,
        /* stdErr */ null);
    TestResultSummary result3 = new TestResultSummary(
        /* testCaseName */ "TestCase",
        /* testName */ "failNoMsg",
        /* isSuccess */ false,
        /* time */ 4000,
        /* message */ null,
        /* stacktrace */ null,
        /* stdOut */ null,
        /* stdErr */ null);
    List<TestResultSummary> resultList = ImmutableList.of(
      result1,
      result2,
      result3);

    TestCaseSummary testCase = new TestCaseSummary("TestCase", resultList);
    List<TestCaseSummary> testCases = ImmutableList.of(testCase);

    TestResults testResults = new TestResults(testCases);
    List<TestResults> testResultsList = ImmutableList.of(testResults);

    // Call the XML generation method with our test data.
    StringWriter writer = new StringWriter();
    TestCommand.writeXmlOutput(testResultsList, writer);
    ByteArrayInputStream resultStream = new ByteArrayInputStream(
      writer.toString().getBytes());

    // Convert the raw XML data into a DOM object, which we will check.
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    DocumentBuilder docBuilder = dbf.newDocumentBuilder();
    Document doc = docBuilder.parse(resultStream);

    // Check for exactly one <tests> tag.
    NodeList testsList = doc.getElementsByTagName("tests");
    assertEquals(testsList.getLength(), 1);

    // Check for exactly one <test> tag.
    Element testsEl = (Element) testsList.item(0);
    NodeList testList = testsEl.getElementsByTagName("test");
    assertEquals(testList.getLength(), 1);

    // Check for exactly three <testresult> tags.
    // There should be two failures and one success.
    Element testEl = (Element) testList.item(0);
    NodeList resultsList = testEl.getElementsByTagName("testresult");
    assertEquals(resultsList.getLength(), 3);

    // Verify the text elements of the first <testresult> tag.
    Element passResultEl = (Element) resultsList.item(0);
    assertEquals(passResultEl.getAttribute("name"), "passTest");
    assertEquals(passResultEl.getAttribute("time"), "5000");
    checkXmlTextContents(passResultEl, "message", "");
    checkXmlTextContents(passResultEl, "stacktrace", "");

    // Verify the text elements of the second <testresult> tag.
    assertEquals(testEl.getAttribute("name"), "TestCase");
    Element failResultEl1 = (Element) resultsList.item(1);
    assertEquals(failResultEl1.getAttribute("name"), "failWithMsg");
    assertEquals(failResultEl1.getAttribute("time"), "7000");
    checkXmlTextContents(failResultEl1, "message", "Index out of bounds!");
    checkXmlTextContents(failResultEl1, "stacktrace", "Stacktrace");

    // Verify the text elements of the third <testresult> tag.
    Element failResultEl2 = (Element) resultsList.item(2);
    assertEquals(failResultEl2.getAttribute("name"), "failNoMsg");
    assertEquals(failResultEl2.getAttribute("time"), "4000");
    checkXmlTextContents(failResultEl2, "message", "");
    checkXmlTextContents(failResultEl2, "stacktrace", "");
  }

  /**
   * Helper method for testXMLGeneration().
   * Used to verify the message and stacktrace fields
   */
  private void checkXmlTextContents(Element testResult,
      String attributeName,
      String expectedValue) {
    // Check for exactly one text element.
    NodeList fieldMatchList = testResult.getElementsByTagName(attributeName);
    assertEquals(fieldMatchList.getLength(), 1);
    Element fieldEl = (Element) fieldMatchList.item(0);

    // Check that the value within the text element is as expected.
    Node firstChild = fieldEl.getFirstChild();
    String expectedStr = Strings.nullToEmpty(expectedValue);
    assertTrue(
      ((firstChild == null) && (expectedStr.equals(""))) ||
      (expectedStr.equals(firstChild.getNodeValue())));
  }

  @Test
  public void testGetCandidateRulesByIncludedLabels() throws CmdLineException {
    TestRule rule1 = new FakeTestRule(BuildRuleType.JAVA_TEST,
        ImmutableSet.of("windows", "linux"),
        BuildTargetFactory.newInstance("//:for"),
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.<BuildTargetPattern>of());

    TestRule rule2 = new FakeTestRule(BuildRuleType.JAVA_TEST,
        ImmutableSet.of("android"),
        BuildTargetFactory.newInstance("//:teh"),
        ImmutableSortedSet.<BuildRule>of(rule1),
        ImmutableSet.<BuildTargetPattern>of());

    TestRule rule3 = new FakeTestRule(BuildRuleType.JAVA_TEST,
        ImmutableSet.of("windows"),
        BuildTargetFactory.newInstance("//:lulz"),
        ImmutableSortedSet.<BuildRule>of(rule2),
        ImmutableSet.<BuildTargetPattern>of());

    Iterable<TestRule> rules = Lists.newArrayList(rule1, rule2, rule3);
    DependencyGraph graph = createDependencyGraphFromBuildRules(rules);
    TestCommandOptions options = getOptions("--include", "linux", "windows");

    Iterable<TestRule> result = TestCommand.getCandidateRulesByIncludedLabels(
        graph, options.getIncludedLabels());
    assertThat(result, IsIterableContainingInAnyOrder.containsInAnyOrder(rule1, rule3));
  }

  @Test
  public void testFilterBuilds() throws CmdLineException {
    TestCommandOptions options = getOptions("--exclude", "linux", "windows");

    TestRule rule1 = new FakeTestRule(BuildRuleType.JAVA_TEST,
        ImmutableSet.of("windows", "linux"),
        BuildTargetFactory.newInstance("//:for"),
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.<BuildTargetPattern>of());

    TestRule rule2 = new FakeTestRule(BuildRuleType.JAVA_TEST,
        ImmutableSet.of("android"),
        BuildTargetFactory.newInstance("//:teh"),
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.<BuildTargetPattern>of());

    TestRule rule3 = new FakeTestRule(BuildRuleType.JAVA_TEST,
        ImmutableSet.of("windows"),
        BuildTargetFactory.newInstance("//:lulz"),
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.<BuildTargetPattern>of());

    List<TestRule> testRules = ImmutableList.of(rule1, rule2, rule3);

    Iterable<TestRule> result = TestCommand.filterTestRules(options, testRules);
    assertThat(result, IsIterableContainingInOrder.contains(rule2));
  }

  @Test
  public void testIsTestRunRequiredForTestInDebugMode() {
    ExecutionContext executionContext = createMock(ExecutionContext.class);
    expect(executionContext.isDebugEnabled()).andReturn(true);
    replay(executionContext);

    assertTrue(TestCommand.isTestRunRequiredForTest(
        createMock(TestRule.class),
        executionContext));

    verify(executionContext);
  }

  @Test
  public void testIsTestRunRequiredForTestBuiltFromCacheIfHasTestResultFiles() {
    ExecutionContext executionContext = createMock(ExecutionContext.class);
    expect(executionContext.isDebugEnabled()).andReturn(false);

    TestRule testRule = createMock(TestRule.class);
    expect(testRule.getBuildResultType()).andReturn(BuildRuleSuccess.Type.FETCHED_FROM_CACHE);
    expect(testRule.hasTestResultFiles(executionContext)).andReturn(true);

    replay(executionContext, testRule);

    assertFalse(TestCommand.isTestRunRequiredForTest(
        testRule,
        executionContext));

    verify(executionContext, testRule);
  }

  @Test
  public void testIsTestRunRequiredForTestBuiltLocally() {
    ExecutionContext executionContext = createMock(ExecutionContext.class);
    expect(executionContext.isDebugEnabled()).andReturn(false);

    TestRule testRule = createMock(TestRule.class);
    expect(testRule.getBuildResultType()).andReturn(BuildRuleSuccess.Type.BUILT_LOCALLY);

    replay(executionContext, testRule);

    assertTrue(TestCommand.isTestRunRequiredForTest(
        testRule,
        executionContext));

    verify(executionContext, testRule);
  }

  @Test
  public void testIfALabelIsIncludedItShouldNotBeExcluded() throws CmdLineException {
    TestCommandOptions options = new TestCommandOptions(new FakeBuckConfig());

    new CmdLineParserAdditionalOptions(options).parseArgument(
        "-e", "e2e", "--exclude", "other", "--include", "e2e");

    ImmutableSet<String> excluded = options.getExcludedLabels();
    assertEquals("other", Iterables.getOnlyElement(excluded));
  }

  @Test
  public void testIfALabelIsIncludedItShouldNotBeExcludedEvenIfTheExcludeIsGlobal()
      throws CmdLineException {
    BuckConfig config = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "test",
            ImmutableMap.of("excluded_labels", "e2e")));
    assertThat(config.getDefaultExcludedLabels(), contains("e2e"));
    TestCommandOptions options = new TestCommandOptions(config);

    new CmdLineParserAdditionalOptions(options).parseArgument("--include", "e2e");

    ImmutableSet<String> included = options.getIncludedLabels();
    assertEquals("e2e", Iterables.getOnlyElement(included));
  }

  @Test
  public void testIncludingATestOnTheCommandLineMeansYouWouldLikeItRun() throws CmdLineException {
    String excludedLabel = "exclude_me";
    BuckConfig config = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "test",
            ImmutableMap.of("excluded_labels", excludedLabel)));
    assertThat(config.getDefaultExcludedLabels(), contains(excludedLabel));
    TestCommandOptions options = new TestCommandOptions(config);

    new CmdLineParserAdditionalOptions(options).parseArgument("//example:test");

    FakeTestRule rule = new FakeTestRule(
        new BuildRuleType("java_test"),
        /* labels */ ImmutableSet.of(excludedLabel),
        BuildTargetFactory.newInstance("//example:test"),
        /* deps */ ImmutableSortedSet.<BuildRule>of(),
        /* visibility */ ImmutableSet.<BuildTargetPattern>of());
    Iterable<TestRule> filtered = TestCommand.filterTestRules(options, ImmutableSet.<TestRule>of(rule));

    assertEquals(rule, Iterables.getOnlyElement(filtered));
  }
}
