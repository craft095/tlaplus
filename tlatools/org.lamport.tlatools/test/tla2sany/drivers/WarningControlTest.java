/*******************************************************************************
 * Copyright (c) 2026 NVIDIA Corp. All rights reserved.
 *
 * The MIT License (MIT)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 ******************************************************************************/
package tla2sany.drivers;

import java.io.File;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import tla2sany.SANYTest;
import tla2sany.modanalyzer.SpecObj;
import tla2sany.output.LogLevel;
import tla2sany.output.RecordedSanyOutput;
import tla2sany.semantic.ErrorCode;
import util.SimpleFilenameToStream;
import util.TestPrintStream;
import util.ToolIO;

/**
 * Tests for the {@code -suppressMessages} and {@code -warningsAsErrors} CLI
 * flags and corresponding {@link SanySettings} API fields, as part of GitHub
 * issue #1186.
 *
 * <p>The fixture spec {@code FieldNameClashWarning.tla} triggers warning 4802
 * ({@link ErrorCode#RECORD_CONSTRUCTOR_FIELD_NAME_CLASH}) reliably: it defines
 * {@code bar == 23} and then constructs {@code [bar |-> 42]}, where the field
 * name {@code bar} clashes with the existing definition.
 */
public class WarningControlTest extends SANYTest {

  private static final String SANY_DIR =
      "test-model" + File.separator + "sany" + File.separator;

  private static final String SPEC_PATH =
      SANY_DIR + "FieldNameClashWarning.tla";

  private static final int CODE_4802 =
      ErrorCode.RECORD_CONSTRUCTOR_FIELD_NAME_CLASH.getStandardValue();

  // ── API-level tests: SANY.parse() + SanySettings ─────────────────────────

  /**
   * Baseline: with default settings the warning fires and SANY succeeds.
   */
  @Test
  public void testWarningAppearsWithDefaultSettings() throws Exception {
    final RecordedSanyOutput out = new RecordedSanyOutput(LogLevel.WARNING);
    final SpecObj spec = new SpecObj(SPEC_PATH, new SimpleFilenameToStream(SANY_DIR));
    final SanyExitCode result = SANY.parse(spec, SPEC_PATH, out, SanySettings.defaultSettings());

    Assert.assertEquals(SanyExitCode.OK, result);
    Assert.assertTrue("Expected field-name-clash warning in output",
        out.getMessages().stream()
            .anyMatch(m -> m.getLevel() == LogLevel.WARNING
                        && m.getText().contains("bar")));
  }

  /**
   * Suppressing code 4802 via {@link SanySettings#suppressedCodes} silences
   * the warning and SANY still succeeds.
   */
  @Test
  public void testSuppressMessagesViaSettings() throws Exception {
    final RecordedSanyOutput out = new RecordedSanyOutput(LogLevel.WARNING);
    final SpecObj spec = new SpecObj(SPEC_PATH, new SimpleFilenameToStream(SANY_DIR));
    final SanySettings settings = new SanySettings(
        true, true, true, true,
        Set.of(CODE_4802),
        Set.of());
    final SanyExitCode result = SANY.parse(spec, SPEC_PATH, out, settings);

    Assert.assertEquals(SanyExitCode.OK, result);
    Assert.assertFalse("Expected no warnings in output when code is suppressed",
        out.getMessages().stream().anyMatch(m -> m.getLevel() == LogLevel.WARNING));
  }

  /**
   * Elevating code 4802 via {@link SanySettings#warningsAsErrorCodes} causes
   * {@link SanyExitCode#SEMANTIC_ANALYSIS_OR_LEVEL_CHECKING_FAILURE} and emits
   * a "Warning treated as error" message at ERROR level.
   */
  @Test
  public void testWarningsAsErrorsViaSettings() throws Exception {
    final RecordedSanyOutput out = new RecordedSanyOutput(LogLevel.WARNING);
    final SpecObj spec = new SpecObj(SPEC_PATH, new SimpleFilenameToStream(SANY_DIR));
    final SanySettings settings = new SanySettings(
        true,         // doStrictErrorCodes
        true, true, true, true,
        Set.of(),
        Set.of(CODE_4802));
    final SanyExitCode result = SANY.parse(spec, SPEC_PATH, out, settings);

    Assert.assertEquals(SanyExitCode.SEMANTIC_ANALYSIS_OR_LEVEL_CHECKING_FAILURE, result);
    Assert.assertTrue("Expected 'Warning treated as error' message at ERROR level",
        out.getMessages().stream()
            .anyMatch(m -> m.getLevel() == LogLevel.ERROR
                        && m.getText().contains("Warning treated as error")));
  }

  // ── CLI tests: SANYmain0() ────────────────────────────────────────────────

  /**
   * {@code -suppressMessages 4802} silences the warning; SANY exits cleanly
   * even with {@code -error-codes}.
   */
  @Test
  public void testCLISuppressMessagesSilencesWarning() throws SANYExitException {
    final TestPrintStream out = new TestPrintStream();
    ToolIO.out = out;
    SANY.SANYmain0(new String[]{"-suppressMessages", "4802", "-error-codes", SPEC_PATH});
    out.assertNoSubstring("field name");
  }

  /**
   * {@code -warningsAsErrors 4802} causes a {@link SANYExitException} with
   * code {@link SanyExitCode#SEMANTIC_ANALYSIS_OR_LEVEL_CHECKING_FAILURE} and
   * prints "Warning treated as error".
   */
  @Test
  public void testCLIWarningsAsErrorsCausesFailure() {
    final TestPrintStream out = new TestPrintStream();
    ToolIO.out = out;
    try {
      SANY.SANYmain0(new String[]{"-warningsAsErrors", "4802", "-error-codes", SPEC_PATH});
      Assert.fail("Expected SANYExitException for elevated warning");
    } catch (SANYExitException e) {
      Assert.assertEquals(
          SanyExitCode.SEMANTIC_ANALYSIS_OR_LEVEL_CHECKING_FAILURE,
          e.getEnumeratedExitCode());
    }
    out.assertSubstring("Warning treated as error");
  }

  /**
   * A comma-separated list of codes works; both codes must be valid SANY
   * codes (4800 and 4802).
   */
  @Test
  public void testCLIMultipleCodesSuppressed() throws SANYExitException {
    final TestPrintStream out = new TestPrintStream();
    ToolIO.out = out;
    SANY.SANYmain0(new String[]{"-suppressMessages", "4800,4802", "-error-codes", SPEC_PATH});
    out.assertNoSubstring("field name");
  }

  /**
   * An unknown code in {@code -suppressMessages} causes an error exit and
   * reports "unknown message code".
   */
  @Test
  public void testCLIUnknownCodeInSuppressMessages() {
    final TestPrintStream out = new TestPrintStream();
    ToolIO.out = out;
    try {
      SANY.SANYmain0(new String[]{"-suppressMessages", "9999", SPEC_PATH});
      Assert.fail("Expected SANYExitException for unknown code");
    } catch (SANYExitException e) {
      Assert.assertEquals(SanyExitCode.ERROR, e.getEnumeratedExitCode());
    }
    out.assertSubstring("unknown message code");
  }

  /**
   * An unknown code in {@code -warningsAsErrors} causes an error exit and
   * reports "unknown message code".
   */
  @Test
  public void testCLIUnknownCodeInWarningsAsErrors() {
    final TestPrintStream out = new TestPrintStream();
    ToolIO.out = out;
    try {
      SANY.SANYmain0(new String[]{"-warningsAsErrors", "9999", SPEC_PATH});
      Assert.fail("Expected SANYExitException for unknown code");
    } catch (SANYExitException e) {
      Assert.assertEquals(SanyExitCode.ERROR, e.getEnumeratedExitCode());
    }
    out.assertSubstring("unknown message code");
  }

  /**
   * Passing an error-level code (4200 = SYMBOL_UNDEFINED) to
   * {@code -warningsAsErrors} is rejected with an error and a message
   * indicating the code is not a warning.
   */
  @Test
  public void testCLINonWarningCodeInWarningsAsErrors() {
    final TestPrintStream out = new TestPrintStream();
    ToolIO.out = out;
    try {
      SANY.SANYmain0(new String[]{"-warningsAsErrors", "4200", SPEC_PATH});
      Assert.fail("Expected SANYExitException for non-warning code");
    } catch (SANYExitException e) {
      Assert.assertEquals(SanyExitCode.ERROR, e.getEnumeratedExitCode());
    }
    out.assertSubstring("not a warning-level code");
  }

  /**
   * {@code -suppressMessages} with no following argument reports an error.
   */
  @Test
  public void testCLISuppressMessagesMissingArgument() {
    try {
      SANY.SANYmain0(new String[]{"-suppressMessages"});
      Assert.fail("Expected SANYExitException when argument is missing");
    } catch (SANYExitException e) {
      Assert.assertEquals(SanyExitCode.ERROR, e.getEnumeratedExitCode());
    }
  }

  /**
   * {@code -warningsAsErrors} with no following argument reports an error.
   */
  @Test
  public void testCLIWarningsAsErrorsMissingArgument() {
    try {
      SANY.SANYmain0(new String[]{"-warningsAsErrors"});
      Assert.fail("Expected SANYExitException when argument is missing");
    } catch (SANYExitException e) {
      Assert.assertEquals(SanyExitCode.ERROR, e.getEnumeratedExitCode());
    }
  }
}
