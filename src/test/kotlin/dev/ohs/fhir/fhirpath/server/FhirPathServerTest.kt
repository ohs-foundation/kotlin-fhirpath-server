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

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

private const val R4 = "/fhirpath-r4"
private const val R4B = "/fhirpath-r4b"
private const val R5 = "/fhirpath-r5"

/**
 * The server's own behaviour: what it serves, what it evaluates, and how it answers a bad request.
 *
 * Conformance with the FHIRPath Lab API is covered separately in [FhirPathLabApiConformanceTest],
 * which tracks an external specification rather than this server's contract.
 */
class FhirPathServerTest {

  private fun runServerTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    application { module() }
    block()
  }

  private suspend fun ApplicationTestBuilder.postFhirJson(endpoint: String, body: String) =
    client.post(endpoint) {
      contentType(ContentType(ContentType.Application.TYPE, "fhir+json"))
      setBody(body)
    }

  @Nested
  inner class ServerInfo {

    @Test
    fun rootReportsWhatTheServerIsAndWhatItServes() = runServerTest {
      val response = client.get("/")

      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(response.bodyAsText().contains("Kotlin FHIRPath server is running!"))
    }

    /**
     * The version the build derives from git, so a deployment can be traced back to the commit it
     * was built from without reading the jar.
     */
    @Test
    fun rootReportsTheServerVersion() = runServerTest {
      val body = client.get("/").bodyAsText()

      assertTrue(body.contains(""""version":"""), body)
      assertFalse(body.contains(""""version":"unknown""""), "version was not resolved: $body")
    }
  }

  @Nested
  inner class Evaluation {

    @ParameterizedTest
    @ValueSource(strings = [R4, R4B, R5])
    fun endpointReturnsFhirParameters(endpoint: String) = runServerTest {
      val response = postFhirJson(endpoint, SPEC_EXAMPLE_REQUEST)

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains(""""resourceType":"Parameters""""))
      assertTrue(body.contains(""""id":"fhirpath""""))
    }

    @ParameterizedTest
    @ValueSource(strings = [R4, R4B, R5])
    fun timeExpressionWithFractionalSecondsIsEvaluated(endpoint: String) = runServerTest {
      val response =
        postFhirJson(
          endpoint,
          """
          {
            "resourceType": "Parameters",
            "parameter": [
              {"name": "expression", "valueString": "@T14:30:00.123"},
              {"name": "resource", "resource": {"resourceType": "Patient"}}
            ]
          }
          """
            .trimIndent(),
        )

      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(response.bodyAsText().contains(""""valueTime":"14:30:00.123""""))
    }
  }

  @Nested
  inner class RequestValidation {

    @ParameterizedTest
    @ValueSource(strings = [R4, R4B, R5])
    fun missingParameterFieldReturnsBadRequest(endpoint: String) = runServerTest {
      val response = postFhirJson(endpoint, """{"resourceType":"Parameters"}""")

      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("OperationOutcome"))
      assertTrue(body.contains("Missing required field: 'parameter'"))
    }

    @ParameterizedTest
    @ValueSource(strings = [R4, R4B, R5])
    fun missingResourceParameterReturnsBadRequest(endpoint: String) = runServerTest {
      val response =
        postFhirJson(
          endpoint,
          """
          {
            "resourceType": "Parameters",
            "parameter": [
              {"name": "expression", "valueString": "name.given"}
            ]
          }
          """
            .trimIndent(),
        )

      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("OperationOutcome"))
      assertTrue(body.contains("Missing required parameter: 'resource'"))
    }

    @ParameterizedTest
    @ValueSource(strings = [R4, R4B, R5])
    fun missingExpressionParameterReturnsBadRequest(endpoint: String) = runServerTest {
      val response =
        postFhirJson(
          endpoint,
          """
          {
            "resourceType": "Parameters",
            "parameter": [
              {"name": "resource", "resource": {"resourceType": "Patient"}}
            ]
          }
          """
            .trimIndent(),
        )

      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("OperationOutcome"))
      assertTrue(body.contains("Missing required parameter: 'expression'"))
    }

    @ParameterizedTest
    @ValueSource(strings = [R4, R4B, R5])
    fun invalidResourceTypeReturnsBadRequest(endpoint: String) = runServerTest {
      val response = postFhirJson(endpoint, """{"resourceType":"Patient"}""")

      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("OperationOutcome"))
      assertTrue(body.contains("Expected FHIR Parameters resource"))
    }

    /**
     * Review comment
     * [#discussion_r3785506611](https://github.com/nawitech/kotlin-fhirpath-server/pull/1#discussion_r3785506611).
     *
     * The body is well-formed JSON in a valid `Parameters` shape, but `valueString` holds an
     * object. `jsonPrimitive` throws `IllegalArgumentException`, which neither `catch` in
     * `parseAndEvaluateFhirPath` handles, so the request escapes as an unhandled exception and Ktor
     * answers with its default HTML 500 page rather than an `OperationOutcome`.
     */
    @ParameterizedTest
    @ValueSource(strings = [R4, R4B, R5])
    fun nonStringExpressionValueReturnsBadRequest(endpoint: String) = runServerTest {
      val response =
        postFhirJson(
          endpoint,
          """
          {
            "resourceType": "Parameters",
            "parameter": [
              {"name": "expression", "valueString": {"x": 1}},
              {"name": "resource", "resource": {"resourceType": "Patient"}}
            ]
          }
          """
            .trimIndent(),
        )

      val body = response.bodyAsText()
      assertEquals(
        HttpStatusCode.BadRequest,
        response.status,
        "A non-string 'valueString' must be rejected, not raise an unhandled exception; body was $body",
      )
      assertTrue(
        body.contains("OperationOutcome"),
        "Errors must be reported as an OperationOutcome, not Ktor's default HTML error page",
      )
    }

    /**
     * Review comment
     * [#discussion_r3785661670](https://github.com/nawitech/kotlin-fhirpath-server/pull/1#discussion_r3785661670).
     *
     * `Parameters.parameter` permits repeated names. `singleOrNull` yielded `null` for two
     * `expression` entries, so the request was rejected as *missing* an expression, pointing at the
     * wrong problem.
     */
    @ParameterizedTest
    @ValueSource(strings = [R4, R4B, R5])
    fun duplicateExpressionParameterIsReportedAsDuplicate(endpoint: String) = runServerTest {
      val response =
        postFhirJson(
          endpoint,
          """
          {
            "resourceType": "Parameters",
            "parameter": [
              {"name": "expression", "valueString": "Patient.id"},
              {"name": "expression", "valueString": "Patient.active"},
              {"name": "resource", "resource": {"resourceType": "Patient", "id": "example"}}
            ]
          }
          """
            .trimIndent(),
        )

      val body = response.bodyAsText()
      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertFalse(
        body.contains("Missing required parameter: 'expression'"),
        "Two 'expression' entries were reported as a missing expression",
      )
      assertTrue(body.contains("Duplicate parameter: 'expression'"), body)
    }

    /**
     * Review comment
     * [#discussion_r3785651351](https://github.com/nawitech/kotlin-fhirpath-server/pull/1#discussion_r3785651351).
     *
     * Variables used to be read only from `valueString`, so a `valueInteger` bound to `null`.
     * FHIRPath propagates empty, so `%n = 5` returned empty instead of `true` — a confidently wrong
     * negative carrying a 200.
     */
    @ParameterizedTest
    @ValueSource(strings = [R4, R4B, R5])
    fun integerVariableValueIsBound(endpoint: String) = runServerTest {
      val response =
        postFhirJson(
          endpoint,
          """
          {
            "resourceType": "Parameters",
            "parameter": [
              {"name": "expression", "valueString": "%n = 5"},
              {"name": "variables", "part": [{"name": "n", "valueInteger": 5}]},
              {"name": "resource", "resource": {"resourceType": "Patient"}}
            ]
          }
          """
            .trimIndent(),
        )

      val body = response.bodyAsText()
      assertEquals(HttpStatusCode.OK, response.status, body)
      assertTrue(
        body.contains(""""valueBoolean":true"""),
        "'%n = 5' with a 'valueInteger' of 5 did not evaluate to true: $body",
      )
    }

    /**
     * The other half of review comment
     * [#discussion_r3785651351](https://github.com/nawitech/kotlin-fhirpath-server/pull/1#discussion_r3785651351):
     * a `value[x]` with no FHIRPath equivalent is rejected rather than bound to `null`.
     *
     * Complex FHIR types would each need decoding into a version-specific model type; until then
     * the request is refused loudly instead of silently losing the binding.
     */
    @ParameterizedTest
    @ValueSource(strings = [R4, R4B, R5])
    fun unsupportedVariableValueTypeReturnsBadRequest(endpoint: String) = runServerTest {
      val response =
        postFhirJson(
          endpoint,
          """
          {
            "resourceType": "Parameters",
            "parameter": [
              {"name": "expression", "valueString": "%n"},
              {
                "name": "variables",
                "part": [{"name": "n", "valueHumanName": {"family": "Chalmers"}}]
              },
              {"name": "resource", "resource": {"resourceType": "Patient"}}
            ]
          }
          """
            .trimIndent(),
        )

      val body = response.bodyAsText()
      assertEquals(HttpStatusCode.BadRequest, response.status, body)
      assertTrue(body.contains("Unsupported value type 'valueHumanName' for variable 'n'"), body)
    }

    /**
     * A quantity is the one complex `value[x]` handled directly: it maps onto the engine's own
     * `FhirPathQuantity` and needs no version-specific FHIR model type.
     */
    @ParameterizedTest
    @ValueSource(strings = [R4, R4B, R5])
    fun quantityVariableValueIsBound(endpoint: String) = runServerTest {
      val response =
        postFhirJson(
          endpoint,
          """
          {
            "resourceType": "Parameters",
            "parameter": [
              {"name": "expression", "valueString": "%q > 2 'mg'"},
              {
                "name": "variables",
                "part": [{"name": "q", "valueQuantity": {"value": 5, "code": "mg"}}]
              },
              {"name": "resource", "resource": {"resourceType": "Patient"}}
            ]
          }
          """
            .trimIndent(),
        )

      val body = response.bodyAsText()
      assertEquals(HttpStatusCode.OK, response.status, body)
      assertTrue(body.contains(""""valueBoolean":true"""), body)
    }

    /**
     * FHIRPath compares quantities by UCUM code, so `code` wins over the `unit` display string —
     * here they disagree, and only the code makes the comparison hold.
     */
    @ParameterizedTest
    @ValueSource(strings = [R4, R4B, R5])
    fun quantityVariableUnitPrefersTheUcumCode(endpoint: String) = runServerTest {
      val response =
        postFhirJson(
          endpoint,
          """
          {
            "resourceType": "Parameters",
            "parameter": [
              {"name": "expression", "valueString": "%q = 5 'mg'"},
              {
                "name": "variables",
                "part": [
                  {"name": "q", "valueQuantity": {"value": 5, "unit": "milligram", "code": "mg"}}
                ]
              },
              {"name": "resource", "resource": {"resourceType": "Patient"}}
            ]
          }
          """
            .trimIndent(),
        )

      val body = response.bodyAsText()
      assertEquals(HttpStatusCode.OK, response.status, body)
      assertTrue(body.contains(""""valueBoolean":true"""), body)
    }

    /**
     * `variables` is 0..* in the
     * [FHIRPath Lab server API](https://github.com/brianpos/fhirpath-lab/blob/develop/server-api.md),
     * so bindings from every occurrence are merged rather than rejected as a duplicate parameter.
     */
    @ParameterizedTest
    @ValueSource(strings = [R4, R4B, R5])
    fun repeatedVariablesParametersAreMerged(endpoint: String) = runServerTest {
      val response =
        postFhirJson(
          endpoint,
          """
          {
            "resourceType": "Parameters",
            "parameter": [
              {"name": "expression", "valueString": "%a & %b"},
              {"name": "variables", "part": [{"name": "a", "valueString": "one"}]},
              {"name": "variables", "part": [{"name": "b", "valueString": "two"}]},
              {"name": "resource", "resource": {"resourceType": "Patient"}}
            ]
          }
          """
            .trimIndent(),
        )

      val body = response.bodyAsText()
      assertEquals(HttpStatusCode.OK, response.status, body)
      assertTrue(body.contains(""""valueString":"onetwo""""), body)
    }

    /**
     * `variables.part.value` is 0..1 in the
     * [FHIRPath Lab server API](https://github.com/brianpos/fhirpath-lab/blob/develop/server-api.md),
     * so a variable declared without a value is legal and binds empty.
     */
    @ParameterizedTest
    @ValueSource(strings = [R4, R4B, R5])
    fun variableWithoutAValueIsAccepted(endpoint: String) = runServerTest {
      val response =
        postFhirJson(
          endpoint,
          """
          {
            "resourceType": "Parameters",
            "parameter": [
              {"name": "expression", "valueString": "%v.empty()"},
              {"name": "variables", "part": [{"name": "v"}]},
              {"name": "resource", "resource": {"resourceType": "Patient"}}
            ]
          }
          """
            .trimIndent(),
        )

      val body = response.bodyAsText()
      assertEquals(HttpStatusCode.OK, response.status, body)
      assertTrue(body.contains(""""valueBoolean":true"""), "'%v' did not bind to empty: $body")
    }

    /**
     * Two bindings for one name: only one can win and the caller cannot tell which, so the request
     * is refused rather than resolved arbitrarily.
     */
    @ParameterizedTest
    @ValueSource(strings = [R4, R4B, R5])
    fun duplicateVariableNameIsRejected(endpoint: String) = runServerTest {
      val response =
        postFhirJson(
          endpoint,
          """
          {
            "resourceType": "Parameters",
            "parameter": [
              {"name": "expression", "valueString": "%a"},
              {"name": "variables", "part": [{"name": "a", "valueString": "one"}]},
              {"name": "variables", "part": [{"name": "a", "valueString": "two"}]},
              {"name": "resource", "resource": {"resourceType": "Patient"}}
            ]
          }
          """
            .trimIndent(),
        )

      val body = response.bodyAsText()
      assertEquals(HttpStatusCode.BadRequest, response.status, body)
      assertTrue(body.contains("Duplicate variable: 'a'"), body)
    }

    /**
     * `receive` fails before the parser sees the body, so this is the one request fault that could
     * still escape as Ktor's default error page rather than an `OperationOutcome`.
     */
    @ParameterizedTest
    @ValueSource(strings = [R4, R4B, R5])
    fun malformedBodyIsReportedAsAnOperationOutcome(endpoint: String) = runServerTest {
      val response = postFhirJson(endpoint, "not json at all")

      val body = response.bodyAsText()
      assertEquals(HttpStatusCode.BadRequest, response.status, body)
      assertTrue(body.contains("OperationOutcome"), body)
      assertTrue(body.contains(""""code":"structure""""), body)
    }

    /** A JSON array is well-formed JSON but not a `Parameters` resource. */
    @ParameterizedTest
    @ValueSource(strings = [R4, R4B, R5])
    fun nonObjectBodyIsReportedAsAnOperationOutcome(endpoint: String) = runServerTest {
      val response = postFhirJson(endpoint, """["not", "a", "resource"]""")

      val body = response.bodyAsText()
      assertEquals(HttpStatusCode.BadRequest, response.status, body)
      assertTrue(body.contains("OperationOutcome"), body)
    }
  }

  private companion object {

    /**
     * The example request from the
     * [FHIRPath Lab server API](https://github.com/brianpos/fhirpath-lab/blob/develop/server-api.md),
     * verbatim — including the `validate` parameter this server does not read and the `variables`
     * parameter with no parts.
     */
    const val SPEC_EXAMPLE_REQUEST =
      """
      {
        "resourceType": "Parameters",
        "parameter": [
          {
            "name": "expression",
            "valueString": "trace('trc').given.join(' ')\n.combine(family).join(', ')"
          },
          {"name": "context", "valueString": "name"},
          {"name": "validate", "valueBoolean": true},
          {"name": "variables"},
          {
            "name": "resource",
            "resource": {
              "resourceType": "Patient",
              "id": "example",
              "identifier": [
                {
                  "use": "usual",
                  "type": {
                    "coding": [
                      {
                        "system": "http://terminology.hl7.org/CodeSystem/v2-0203",
                        "code": "MR"
                      }
                    ]
                  },
                  "system": "urn:oid:1.2.36.146.595.217.0.1",
                  "value": "12345",
                  "period": {"start": "2001-05-06"},
                  "assigner": {"display": "Acme Healthcare"}
                }
              ],
              "active": true,
              "name": [
                {"use": "official", "family": "Chalmers", "given": ["Peter", "James"]},
                {"use": "usual", "given": ["Jim"]},
                {
                  "use": "maiden",
                  "family": "Windsor",
                  "given": ["Peter", "James"],
                  "period": {"end": "2002"}
                }
              ],
              "telecom": [
                {"use": "home"},
                {"system": "phone", "value": "(03) 5555 6473", "use": "work", "rank": 1},
                {"system": "phone", "value": "(03) 3410 5613", "use": "mobile", "rank": 2},
                {
                  "system": "phone",
                  "value": "(03) 5555 8834",
                  "use": "old",
                  "period": {"end": "2014"}
                }
              ],
              "gender": "male",
              "birthDate": "1974-12-25",
              "_birthDate": {
                "extension": [
                  {
                    "url": "http://hl7.org/fhir/StructureDefinition/patient-birthTime",
                    "valueDateTime": "1974-12-25T14:35:45-05:00"
                  }
                ]
              },
              "deceasedBoolean": false,
              "address": [
                {
                  "use": "home",
                  "type": "both",
                  "text": "534 Erewhon St PeasantVille, Rainbow, Vic  3999",
                  "line": ["534 Erewhon St"],
                  "city": "PleasantVille",
                  "district": "Rainbow",
                  "state": "Vic",
                  "postalCode": "3999",
                  "period": {"start": "1974-12-25"}
                }
              ],
              "contact": [
                {
                  "relationship": [
                    {
                      "coding": [
                        {
                          "system": "http://terminology.hl7.org/CodeSystem/v2-0131",
                          "code": "N"
                        }
                      ]
                    }
                  ],
                  "name": {
                    "family": "du Marché",
                    "_family": {
                      "extension": [
                        {
                          "url": "http://hl7.org/fhir/StructureDefinition/humanname-own-prefix",
                          "valueString": "VV"
                        }
                      ]
                    },
                    "given": ["Bénédicte"]
                  },
                  "telecom": [{"system": "phone", "value": "+33 (237) 998327"}],
                  "address": {
                    "use": "home",
                    "type": "both",
                    "line": ["534 Erewhon St"],
                    "city": "PleasantVille",
                    "district": "Rainbow",
                    "state": "Vic",
                    "postalCode": "3999",
                    "period": {"start": "1974-12-25"}
                  },
                  "gender": "female",
                  "period": {"start": "2012"}
                }
              ],
              "managingOrganization": {"reference": "Organization/1"}
            }
          },
          {
            "name": "terminologyserver",
            "valueString": "https://sqlonfhir-r4.azurewebsites.net/fhir"
          }
        ]
      }
      """
  }
}
