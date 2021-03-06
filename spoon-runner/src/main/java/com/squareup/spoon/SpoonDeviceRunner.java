package com.squareup.spoon;

import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.CollectingOutputReceiver;
import com.android.ddmlib.DdmPreferences;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.InstallException;
import com.android.ddmlib.logcat.LogCatMessage;
import com.android.ddmlib.testrunner.IRemoteAndroidTestRunner;
import com.android.ddmlib.testrunner.ITestRunListener;
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner;
import com.android.ddmlib.testrunner.TestIdentifier;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;

import static com.android.ddmlib.FileListingService.FileEntry;
import static com.android.ddmlib.SyncService.getNullProgressMonitor;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.squareup.spoon.SpoonLogger.logDebug;
import static com.squareup.spoon.SpoonLogger.logError;
import static com.squareup.spoon.SpoonLogger.logInfo;
import static com.squareup.spoon.SpoonUtils.createAnimatedGif;
import static com.squareup.spoon.SpoonUtils.obtainDirectoryFileEntry;
import static com.squareup.spoon.SpoonUtils.obtainRealDevice;
import static com.squareup.spoon.internal.Constants.SPOON_FILES;
import static com.squareup.spoon.internal.Constants.SPOON_SCREENSHOTS;
import static java.util.Collections.emptyMap;

/** Represents a single device and the test configuration to be executed. */
public final class SpoonDeviceRunner {
  private static final String DEVICE_SCREENSHOT_DIR = "app_" + SPOON_SCREENSHOTS;
  private static final String DEVICE_FILE_DIR = "app_" + SPOON_FILES;
  private static final String[] DEVICE_DIRS = {DEVICE_SCREENSHOT_DIR, DEVICE_FILE_DIR};
  static final String TEMP_DIR = "work";
  static final String JUNIT_DIR = "junit-reports";
  static final String IMAGE_DIR = "image";
  static final String FILE_DIR = "file";
  static final String COVERAGE_FILE = "coverage.ec";
  static final String COVERAGE_DIR = "coverage";

  private final File testApk;
  private final List<File> otherApks;
  private final String serial;
  private final int shardIndex;
  private final int numShards;
  private final boolean debug;
  private final boolean noAnimations;
  private final Duration adbTimeout;
  private final List<String> instrumentationArgs;
  private final String className;
  private final String methodName;
  private final IRemoteAndroidTestRunner.TestSize testSize;
  private final File work;
  private final File junitReport;
  private final File imageDir;
  private final File coverageDir;
  private final File fileDir;
  private final SpoonInstrumentationInfo instrumentationInfo;
  private boolean codeCoverage;
  private final List<ITestRunListener> testRunListeners;
  private final boolean grantAll;

  /**
   * Create a test runner for a single device.
   *
   * @param testApk Path to test APK.
   * @param otherApks Paths to additional APKs.
   * @param output Path to output directory.
   * @param serial Device to run the test on.
   * @param debug Whether or not debug logging is enabled.
   * @param adbTimeout time in ms for longest test execution
   * @param instrumentationInfo Test apk manifest information.
   * @param className Test class name to run or {@code null} to run all tests.
   * @param methodName Test method name to run or {@code null} to run all tests.  Must also pass
   * {@code className}.
   * @param testRunListeners Additional TestRunListener or empty list.
   */
  SpoonDeviceRunner(File testApk, List<File> otherApks, File output, String serial, int shardIndex,
      int numShards, boolean debug, boolean noAnimations, Duration adbTimeout,
      SpoonInstrumentationInfo instrumentationInfo, List<String> instrumentationArgs,
      String className, String methodName, IRemoteAndroidTestRunner.TestSize testSize,
      List<ITestRunListener> testRunListeners, boolean codeCoverage, boolean grantAll) {
    this.testApk = testApk;
    this.otherApks = otherApks;
    this.serial = serial;
    this.shardIndex = shardIndex;
    this.numShards = numShards;
    this.debug = debug;
    this.noAnimations = noAnimations;
    this.adbTimeout = adbTimeout;
    this.instrumentationArgs = instrumentationArgs;
    this.className = className;
    this.methodName = methodName;
    this.testSize = testSize;
    this.instrumentationInfo = instrumentationInfo;
    this.codeCoverage = codeCoverage;
    serial = SpoonUtils.sanitizeSerial(serial);
    this.work = FileUtils.getFile(output, TEMP_DIR, serial);
    this.junitReport = FileUtils.getFile(output, JUNIT_DIR, serial + ".xml");
    this.imageDir = FileUtils.getFile(output, IMAGE_DIR, serial);
    this.fileDir = FileUtils.getFile(output, FILE_DIR, serial);
    this.coverageDir = FileUtils.getFile(output, COVERAGE_DIR, serial);
    this.testRunListeners = testRunListeners;
    this.grantAll = grantAll;
  }

  private void printStream(InputStream stream, String tag) throws IOException {
    try (BufferedReader stdout = new BufferedReader(new InputStreamReader(stream))) {
      String s;
      while ((s = stdout.readLine()) != null) {
        logDebug(debug, "[%s] %s %s", serial, tag, s);
      }
    }
  }

  /** Execute instrumentation on the target device and return a result summary. */
  public DeviceResult run(AndroidDebugBridge adb) {
    String testPackage = instrumentationInfo.getInstrumentationPackage();
    String testRunner = instrumentationInfo.getTestRunnerClass();

    logDebug(debug, "InstrumentationInfo: [%s]", instrumentationInfo);

    if (debug) {
      SpoonUtils.setDdmlibInternalLoggingLevel();
    }

    DeviceResult.Builder result = new DeviceResult.Builder();

    IDevice device = obtainRealDevice(adb, serial);
    logDebug(debug, "Got realDevice for [%s]", serial);

    // Get relevant device information.
    final DeviceDetails deviceDetails = DeviceDetails.createForDevice(device);
    result.setDeviceDetails(deviceDetails);
    logDebug(debug, "[%s] setDeviceDetails %s", serial, deviceDetails);

    DdmPreferences.setTimeOut((int) adbTimeout.toMillis());

    // Now install the main application and the instrumentation application.
    for (File otherApk : otherApks) {
      try {
        String extraArgument = "";
        if (grantAll && deviceDetails.getApiLevel() >= DeviceDetails.MARSHMALLOW_API_LEVEL) {
          extraArgument = "-g";
        }
        device.installPackage(otherApk.getAbsolutePath(), true, extraArgument);
      } catch (InstallException e) {
        logInfo("InstallException while install other apk on device [%s]", serial);
        e.printStackTrace(System.out);
        return result.markInstallAsFailed("Unable to install other APK.").addException(e).build();
      }
    }
    try {
      device.installPackage(testApk.getAbsolutePath(), true);
    } catch (InstallException e) {
      logInfo("InstallException while install test apk on device [%s]", serial);
      e.printStackTrace(System.out);
      return result.markInstallAsFailed("Unable to install instrumentation APK.")
          .addException(e)
          .build();
    }

    // If this is Android Marshmallow or above grant WRITE_EXTERNAL_STORAGE
    if (deviceDetails.getApiLevel() >= DeviceDetails.MARSHMALLOW_API_LEVEL) {
      String appPackage = instrumentationInfo.getApplicationPackage();
      try {
        CollectingOutputReceiver grantOutputReceiver = new CollectingOutputReceiver();
        device.executeShellCommand(
            "pm grant " + appPackage + " android.permission.READ_EXTERNAL_STORAGE",
            grantOutputReceiver);
        device.executeShellCommand(
            "pm grant " + appPackage + " android.permission.WRITE_EXTERNAL_STORAGE",
            grantOutputReceiver);
      } catch (Exception e) {
        logInfo("Exception while granting external storage access to application apk"
            + "on device [%s]", serial);
        e.printStackTrace(System.out);
        return result.markInstallAsFailed(
            "Unable to grant external storage access to application APK.").addException(e).build();
      }
    }

    // Create the output directory, if it does not already exist.
    work.mkdirs();

    LogRecordingTestRunListener recorder = new LogRecordingTestRunListener();
    try {
      logDebug(debug, "Querying a list of tests on [%s]", serial);
      RemoteAndroidTestRunner runner = createConfiguredRunner(testPackage, testRunner, device);
      runner.addBooleanArg("log", true);
      // Add the sharding instrumentation arguments if necessary
      if (numShards != 0) {
        addShardingInstrumentationArgs(runner);
      }
      if (!isNullOrEmpty(className)) {
        if (isNullOrEmpty(methodName)) {
          runner.setClassName(className);
        } else {
          runner.setMethodName(className, methodName);
        }
      }
      if (testSize != null) {
        runner.setTestSize(testSize);
      }
      runner.run(recorder);
    } catch (Exception e) {
    }
    List<TestIdentifier> activeTests = recorder.activeTests();
    List<TestIdentifier> ignoredTests = recorder.ignoredTests();
    logDebug(debug, "Active tests: %s", activeTests);
    logDebug(debug, "Ignored tests: %s", ignoredTests);

    // Initiate device logging.
    SpoonDeviceLogger deviceLogger = new SpoonDeviceLogger(device);

    List<ITestRunListener> listeners = new ArrayList<>();
    listeners.add(new SpoonTestRunListener(result, debug));
    listeners.add(new XmlTestRunListener(junitReport));
    if (testRunListeners != null) {
      listeners.addAll(testRunListeners);
    }
    MultiRunITestListener multiRunListener = new MultiRunITestListener(listeners);

    result.startTests();
    multiRunListener.multiRunStarted(recorder.runName(), recorder.testCount());
    for (TestIdentifier test : activeTests) {
      logDebug(debug, "Running %s [%s]", test, serial);
      try {
        RemoteAndroidTestRunner runner = createConfiguredRunner(testPackage, testRunner, device);
        if (codeCoverage) {
          addCodeCoverageInstrumentationArgs(runner, device);
        }
        runner.setMethodName(test.getClassName(), test.getTestName());
        runner.run(listeners);
      } catch (Exception e) {
        result.addException(e);
        break;
      }
    }
    for (TestIdentifier ignoredTest : ignoredTests) {
      multiRunListener.testStarted(ignoredTest);
      multiRunListener.testIgnored(ignoredTest);
      multiRunListener.testEnded(ignoredTest, emptyMap());
    }
    multiRunListener.multiRunEnded();
    result.endTests();

    mapLogsToTests(deviceLogger, result);

    try {
      logDebug(debug, "About to grab screenshots and prepare output for [%s]", serial);
      pullDeviceFiles(device);
      if (codeCoverage) {
        pullCoverageFile(device);
      }

      cleanScreenshotsDirectory(result);
      cleanFilesDirectory(result);

    } catch (Exception e) {
      result.addException(e);
    }
    logDebug(debug, "Done running for [%s]", serial);

    return result.build();
  }

  private RemoteAndroidTestRunner createConfiguredRunner(String testPackage, String testRunner,
      IDevice device) throws Exception {
    RemoteAndroidTestRunner runner = new RemoteAndroidTestRunner(testPackage, testRunner, device);
    runner.setMaxTimeToOutputResponse(adbTimeout.toMillis(), TimeUnit.MILLISECONDS);

    // TODO do something about this. Use an ImmutableMultimap<String, String> in the model?
    if (instrumentationArgs != null && instrumentationArgs.size() > 0) {
      for (String pair : instrumentationArgs) {
        int firstEqualSignIndex = pair.indexOf("=");
        if (firstEqualSignIndex <= -1) {
          // No Equal Sign, can't process
          logDebug(debug, "Can't process instrumentationArg [%s] (no equal sign)", pair);
          continue;
        }
        String key = pair.substring(0, firstEqualSignIndex);
        String value = pair.substring(firstEqualSignIndex + 1);
        if (isNullOrEmpty(key) || isNullOrEmpty(value)) {
          // Invalid values, skipping
          logDebug(debug, "Can't process instrumentationArg [%s] (empty key or value)", pair);
          continue;
        }
        runner.addInstrumentationArg(key, value);
      }
    }

    return runner;
  }

  private void addCodeCoverageInstrumentationArgs(RemoteAndroidTestRunner runner, IDevice device)
          throws Exception {
    String coveragePath = getExternalStoragePath(device, COVERAGE_FILE);
    runner.addInstrumentationArg("coverage", "true");
    runner.addInstrumentationArg("coverageFile", coveragePath);
  }

  private void addShardingInstrumentationArgs(RemoteAndroidTestRunner runner) {
    runner.addInstrumentationArg("numShards", Integer.toString(numShards));
    runner.addInstrumentationArg("shardIndex", Integer.toString(shardIndex));
  }

  private void cleanScreenshotsDirectory(DeviceResult.Builder result) throws IOException {
    File screenshotDir = new File(work, DEVICE_SCREENSHOT_DIR);
    if (screenshotDir.exists()) {
      imageDir.mkdirs();
      handleImages(result, screenshotDir);
      FileUtils.deleteDirectory(screenshotDir);
    }
  }

  private void cleanFilesDirectory(DeviceResult.Builder result) throws IOException {
    File testFilesDir = new File(work, DEVICE_FILE_DIR);
    if (testFilesDir.exists()) {
      fileDir.mkdirs();
      handleFiles(result, testFilesDir);
      FileUtils.deleteDirectory(testFilesDir);
    }
  }

  private void pullCoverageFile(IDevice device) {
    coverageDir.mkdirs();
    File coverageFile = new File(coverageDir, COVERAGE_FILE);
    String remotePath;
    try {
      remotePath = getExternalStoragePath(device, COVERAGE_FILE);
    } catch (Exception exception) {
      throw new RuntimeException("error while calculating coverage file path.", exception);
    }
    adbPullFile(device, remotePath, coverageFile.getAbsolutePath());
  }

  private void handleImages(DeviceResult.Builder result, File screenshotDir) throws IOException {
    logDebug(debug, "Moving screenshots to the image folder on [%s]", serial);
    // Move all children of the screenshot directory into the image folder.
    File[] classNameDirs = screenshotDir.listFiles();
    if (classNameDirs != null) {
      Multimap<DeviceTest, File> testScreenshots = ArrayListMultimap.create();
      for (File classNameDir : classNameDirs) {
        String className = classNameDir.getName();
        File destDir = new File(imageDir, className);
        FileUtils.copyDirectory(classNameDir, destDir);

        // Get a sorted list of all screenshots from the device run.
        List<File> screenshots = new ArrayList<>(
            FileUtils.listFiles(destDir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE));
        Collections.sort(screenshots);

        // Iterate over each screenshot and associate it with its corresponding method result.
        for (File screenshot : screenshots) {
          String methodName = screenshot.getParentFile().getName();

          DeviceTest testIdentifier = new DeviceTest(className, methodName);
          DeviceTestResult.Builder builder = result.getMethodResultBuilder(testIdentifier);
          if (builder != null) {
            builder.addScreenshot(screenshot);
            testScreenshots.put(testIdentifier, screenshot);
          } else {
            logError("Unable to find test for %s", testIdentifier);
          }
        }
      }

      logDebug(debug, "Generating animated gifs for [%s]", serial);
      // Don't generate animations if the switch is present
      if (!noAnimations) {
        // Make animated GIFs for all the tests which have screenshots.
        for (DeviceTest deviceTest : testScreenshots.keySet()) {
          List<File> screenshots = new ArrayList<>(testScreenshots.get(deviceTest));
          if (screenshots.size() == 1) {
            continue; // Do not make an animated GIF if there is only one screenshot.
          }
          File animatedGif = FileUtils.getFile(imageDir, deviceTest.getClassName(),
              deviceTest.getMethodName() + ".gif");
          createAnimatedGif(screenshots, animatedGif);
          result.getMethodResultBuilder(deviceTest).setAnimatedGif(animatedGif);
        }
      }
    }
  }

  private void handleFiles(DeviceResult.Builder result, File testFileDir) throws IOException {
    File[] classNameDirs = testFileDir.listFiles();
    if (classNameDirs != null) {
      logInfo("Found class name dirs: " + Arrays.toString(classNameDirs));
      for (File classNameDir : classNameDirs) {
        String className = classNameDir.getName();
        File destDir = new File(fileDir, className);
        FileUtils.copyDirectory(classNameDir, destDir);
        logInfo("Copied " + classNameDir + " to " + destDir);

        // Get a sorted list of all files from the device run.
        List<File> files = new ArrayList<>(
            FileUtils.listFiles(destDir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE));
        Collections.sort(files);

        // Iterate over each file and associate it with its
        // corresponding method result.
        for (File file : files) {
          String methodName = file.getParentFile().getName();
          DeviceTest testIdentifier = new DeviceTest(className, methodName);
          final DeviceTestResult.Builder resultBuilder =
              result.getMethodResultBuilder(testIdentifier);
          if (resultBuilder != null) {
            resultBuilder.addFile(file);
            logInfo("Added file as result: " + file + " for " + testIdentifier);
          } else {
            logError("Unable to find test for %s", testIdentifier);
          }
        }
      }
    }
  }

  /** Download all files from a single device to the local machine. */
  private void pullDeviceFiles(IDevice device) throws Exception {
    for (String dir : DEVICE_DIRS) {
      pullDirectory(device, dir);
    }
  }

  private void pullDirectory(final IDevice device, final String name) throws Exception {
    // Output path on private internal storage, for KitKat and below.
    FileEntry internalDir = getDirectoryOnInternalStorage(name);
    logDebug(debug, "Internal path is " + internalDir.getFullPath());

    // Output path on public external storage, for Lollipop and above.
    FileEntry externalDir = getDirectoryOnExternalStorage(device, name);
    logDebug(debug, "External path is " + externalDir.getFullPath());

    // Sync test output files to the local filesystem.
    logDebug(debug, "Pulling files from external dir on [%s]", serial);
    String localDirName = work.getAbsolutePath();
    adbPull(device, externalDir, localDirName);
    logDebug(debug, "Pulling files from internal dir on [%s]", serial);
    adbPull(device, internalDir, localDirName);
    logDebug(debug, "Done pulling %s from on [%s]", name, serial);
  }

  private void adbPull(IDevice device, FileEntry remoteDirName, String localDirName) {
    try {
      device.getSyncService().pull(new FileEntry[]{remoteDirName}, localDirName,
          getNullProgressMonitor());
    } catch (Exception e) {
      logDebug(debug, e.getMessage(), e);
    }
  }

  private void adbPullFile(IDevice device, String remoteFile, String localDir) {
    try {
      device.getSyncService()
          .pullFile(remoteFile, localDir, getNullProgressMonitor());
    } catch (Exception e) {
      logDebug(debug, e.getMessage(), e);
    }
  }

  private FileEntry getDirectoryOnInternalStorage(final String dir) {
    String internalPath = getInternalPath(dir);
    return obtainDirectoryFileEntry(internalPath);
  }

  private String getInternalPath(String path) {
    String appPackage = instrumentationInfo.getApplicationPackage();
    return "/data/data/" + appPackage + "/" + path;
  }

  private FileEntry getDirectoryOnExternalStorage(IDevice device, final String dir)
      throws Exception {
    String externalPath = getExternalStoragePath(device, dir);
    return obtainDirectoryFileEntry(externalPath);
  }

  private String getExternalStoragePath(IDevice device, final String path) throws Exception {
    CollectingOutputReceiver pathNameOutputReceiver = new CollectingOutputReceiver();
    device.executeShellCommand("echo $EXTERNAL_STORAGE", pathNameOutputReceiver);
    return pathNameOutputReceiver.getOutput().trim() + "/" + path;
  }

  /** Grab all the parsed logs and map them to individual tests. */
  private static void mapLogsToTests(SpoonDeviceLogger deviceLogger, DeviceResult.Builder result) {
    Map<DeviceTest, List<LogCatMessage>> logs = deviceLogger.getParsedLogs();
    for (Map.Entry<DeviceTest, List<LogCatMessage>> entry : logs.entrySet()) {
      DeviceTestResult.Builder builder = result.getMethodResultBuilder(entry.getKey());
      if (builder != null) {
        builder.setLog(entry.getValue());
      }
    }
  }
}
