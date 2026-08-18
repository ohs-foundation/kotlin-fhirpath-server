/*
 * Copyright 2026 Open Health Stack Foundation
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

data class InputData(
  val contextExpression: String?,
  val expression: String,
  val resourceStr: String,
  /**
   * Variable bindings, keyed by name. Values are the FHIRPath primitives the engine understands —
   * [String], [Boolean], [Int], `BigDecimal`, `FhirPathDate`, `FhirPathDateTime`, `FhirPathTime` —
   * or `null` for a variable declared with no `value[x]`, which `variables.part.value` (0..1)
   * permits.
   */
  val variables: Map<String, Any?>,
  val terminologyServer: String?,
)
