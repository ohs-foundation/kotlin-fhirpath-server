/*
 * Copyright 2025-2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ohs.fhir.fhirpath.server

import java.util.Properties

/**
 * The server's version, as derived from git at build time by `build.gradle.kts` (see
 * `generateVersionResource`). Bundled as a classpath resource so it's readable the same way
 * regardless of how the app is packaged or run.
 */
object AppVersion {
  private val properties: Properties by lazy {
    Properties().apply {
      AppVersion::class.java.getResourceAsStream("/version.properties")?.use { load(it) }
    }
  }

  /** This server's version. */
  val current: String by lazy { properties.getProperty("version") ?: "unknown" }

  /** Version of the `fhir-path` engine library this server evaluates with. */
  val engine: String by lazy { properties.getProperty("engineVersion") ?: "unknown" }
}
