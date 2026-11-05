/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.log4j.nt;

import java.io.File;
import java.io.IOException;

import junit.framework.TestCase;
import org.apache.log4j.LogManager;


/**
 * Tests resolution of the NTEventLogAppender DLL through the
 * log4j.nteventlog.dll.path system property (DLL preloading mitigation).
 * Unlike NTEventLogAppenderTest these tests do not use the Windows event log
 * and run on every platform.
 */
public class NTEventLogAppenderDllPathTest extends TestCase {

  /**
   * Absolute path of the native library handed to NTEventLogAppender through
   * the log4j.nteventlog.dll.path system property when the class is initialized,
   * null if no such library could be located.
   */
  private static String configuredLibraryPath;

  /**
   * Error raised while initializing NTEventLogAppender, null on success.
   */
  private static Throwable initializationError;

  private static boolean appenderClassInitialized = false;

  private String originalDllPath;

  /**
   * Initializes NTEventLogAppender once per JVM with log4j.nteventlog.dll.path
   * set to the absolute path of a native library that is not named
   * NTEventLogAppender. On a system without NTEventLogAppender on
   * PATH/java.library.path initialization can only succeed if the appender
   * loads the configured absolute path instead of searching the library path.
   */
  private static synchronized void initializeAppenderClass() {
    if (appenderClassInitialized) {
      return;
    }
    appenderClassInitialized = true;
    String original = System.getProperty(NTEventLogAppender.DLL_PATH_PROPERTY);
    configuredLibraryPath = findLoadableNativeLibrary();
    try {
      if (configuredLibraryPath != null) {
        System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, configuredLibraryPath);
      }
      Class.forName("org.apache.log4j.nt.NTEventLogAppender");
    } catch (Throwable t) {
      initializationError = t;
    } finally {
      if (original != null) {
        System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, original);
      } else {
        System.clearProperty(NTEventLogAppender.DLL_PATH_PROPERTY);
      }
    }
  }

  /**
   * Locates a JRE native library that has no dependency on the Windows
   * event log and is not loaded by the JVM at startup.
   * @return absolute path of the library, null if none found.
   */
  private static String findLoadableNativeLibrary() {
    String bootPath = System.getProperty("sun.boot.library.path");
    if (bootPath == null) {
      return null;
    }
    String[] names = new String[] { "jaas_unix", "jaas_nt", "jaas" };
    String[] dirs = bootPath.split(File.pathSeparator);
    for (int i = 0; i < names.length; i++) {
      String libName = System.mapLibraryName(names[i]);
      for (int j = 0; j < dirs.length; j++) {
        if (dirs[j].length() == 0) {
          continue;
        }
        File candidate = new File(dirs[j], libName);
        if (candidate.isAbsolute() && candidate.isFile()) {
          return candidate.getAbsolutePath();
        }
      }
    }
    return null;
  }

  public void setUp() {
    initializeAppenderClass();
    originalDllPath = System.getProperty(NTEventLogAppender.DLL_PATH_PROPERTY);
    System.clearProperty(NTEventLogAppender.DLL_PATH_PROPERTY);
  }

  public void tearDown() {
    if (originalDllPath != null) {
      System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, originalDllPath);
    } else {
      System.clearProperty(NTEventLogAppender.DLL_PATH_PROPERTY);
    }
    LogManager.shutdown();
  }

  /**
   * When log4j.nteventlog.dll.path names an absolute, existing library, the
   * appender must load exactly that file via System.load instead of searching
   * PATH/java.library.path (where a planted NTEventLogAppender DLL could be
   * picked up).
   */
  public void testConfiguredAbsoluteDllPathIsLoadedDirectly() {
    assertNotNull("No native library found under sun.boot.library.path", configuredLibraryPath);
    if (initializationError != null) {
      fail("NTEventLogAppender did not load the configured library "
          + configuredLibraryPath + ": " + initializationError);
    }
  }

  /**
   * When the system property is not set, resolveConfiguredDllPath should return null.
   */
  public void testResolveConfiguredDllPath_notSet() {
    System.clearProperty(NTEventLogAppender.DLL_PATH_PROPERTY);
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  /**
   * When the system property is an empty string, resolveConfiguredDllPath should return null.
   */
  public void testResolveConfiguredDllPath_emptyString() {
    System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, "");
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  /**
   * When the system property is whitespace only, resolveConfiguredDllPath should return null.
   */
  public void testResolveConfiguredDllPath_whitespaceOnly() {
    System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, "   ");
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  /**
   * When the system property is a relative path, resolveConfiguredDllPath should return null.
   */
  public void testResolveConfiguredDllPath_relativePath() {
    System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, "relative/path/NTEventLogAppender.dll");
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  /**
   * A relative path must be rejected even when a matching DLL has been planted
   * in the current working directory, since it would resolve against the
   * working directory.
   */
  public void testResolveConfiguredDllPath_relativePathToPlantedFile() throws IOException {
    File workingDir = new File(System.getProperty("user.dir"));
    File planted = File.createTempFile("NTEventLogAppender", ".dll", workingDir);
    planted.deleteOnExit();
    try {
      assertTrue(new File(planted.getName()).exists());
      System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, planted.getName());
      assertNull(NTEventLogAppender.resolveConfiguredDllPath());
    } finally {
      planted.delete();
    }
  }

  /**
   * When the system property is an absolute path to a file that does not exist,
   * resolveConfiguredDllPath should return null.
   */
  public void testResolveConfiguredDllPath_absoluteButMissing() {
    String missingPath = new File(System.getProperty("java.io.tmpdir"), "no_such_NTEventLogAppender.dll")
        .getAbsolutePath();
    System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, missingPath);
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  /**
   * When the system property is an absolute path to an existing file,
   * resolveConfiguredDllPath should return that absolute path.
   */
  public void testResolveConfiguredDllPath_absoluteAndExists() throws IOException {
    File tempFile = File.createTempFile("NTEventLogAppender", ".dll");
    tempFile.deleteOnExit();
    try {
      String absolutePath = tempFile.getAbsolutePath();
      System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, absolutePath);
      String result = NTEventLogAppender.resolveConfiguredDllPath();
      assertNotNull(result);
      assertEquals(absolutePath, result);
    } finally {
      tempFile.delete();
    }
  }
}
