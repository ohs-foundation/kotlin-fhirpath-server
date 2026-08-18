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

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Conformance with the
 * [FHIRPath Lab server engine API](https://github.com/brianpos/fhirpath-lab/blob/develop/server-api.md).
 */
class FhirPathLabApiConformanceTest {

  private val patient =
    """
    {"resourceType":"Patient","id":"example","active":true,
     "name":[{"use":"official","family":"Chalmers","given":["Peter","James"]},
             {"use":"usual","given":["Jim"]}],
     "contact":[{"gender":"female","name":{"family":"du Marche"}}]}
    """
      .trimIndent()

  private fun requestBody(expression: String, context: String? = null, resource: String = patient) =
    """
    {"resourceType":"Parameters","parameter":[
      {"name":"expression","valueString":"$expression"},
      ${if (context != null) """{"name":"context","valueString":"$context"},""" else ""}
      {"name":"resource","resource":$resource}
    ]}
    """
      .trimIndent()

  private suspend fun io.ktor.client.HttpClient.evaluate(
    expression: String,
    context: String? = null,
    endpoint: String = "/fhirpath-r4",
    resource: String = patient,
  ) =
    post(endpoint) {
      contentType(ContentType(ContentType.Application.TYPE, "fhir+json"))
      setBody(requestBody(expression, context, resource))
    }

  /**
   * Evaluation notes: with no context expression, `%context`, `%resource` and `%rootResource` are
   * all the test resource.
   */
  @ParameterizedTest
  @ValueSource(strings = ["%resource.id", "%rootResource.id", "%context.id"])
  fun standardVariablesAreBoundToTheTestResource(expression: String) = testApplication {
    application { module() }

    val response = client.evaluate(expression)

    val body = response.bodyAsText()
    assertEquals(HttpStatusCode.OK, response.status, body)
    assertTrue(body.contains(""""valueString":"example""""), "$expression did not resolve: $body")
  }

  /**
   * Evaluation notes: with a context expression, `%context` is each item the context expression
   * returned — not the test resource.
   */
  @Test
  fun contextVariableIsBoundPerContextItem() = testApplication {
    application { module() }

    val response = client.evaluate("%context.use", context = "name")

    val body = response.bodyAsText()
    assertEquals(HttpStatusCode.OK, response.status, body)
    assertTrue(body.contains(""""valueString":"official""""), body)
    assertTrue(body.contains(""""valueString":"usual""""), body)
  }

  /** `evaluator` is 1..1 and carries engine name, engine version, and FHIR version in brackets. */
  @ParameterizedTest
  @CsvSource("/fhirpath-r4,R4", "/fhirpath-r4b,R4B", "/fhirpath-r5,R5")
  fun evaluatorReportsEngineAndFhirVersion(endpoint: String, fhirVersion: String) =
    testApplication {
      application { module() }

      val body = client.evaluate("id", endpoint = endpoint).bodyAsText()

      val evaluator =
        Regex(""""name":"evaluator","valueString":"([^"]+)"""").find(body)?.groupValues?.get(1)
      assertTrue(
        evaluator != null && Regex("""Kotlin FHIRPath \S+ \($fhirVersion\)""").matches(evaluator),
        "evaluator must read like 'Kotlin FHIRPath <version> ($fhirVersion)', was: $evaluator",
      )
      assertFalse(evaluator.contains("unknown"), "engine version was not resolved: $evaluator")
    }

  /**
   * A fault in the submitted expression is the caller's, not the server's. It used to surface as a
   * 500 reading `Internal server error: token index 7 out of range 0..6`.
   */
  @ParameterizedTest
  @ValueSource(strings = ["this is not valid fhirpath (((", "'a' + 1"])
  fun expressionFaultsAreReportedAsBadRequest(expression: String) = testApplication {
    application { module() }

    val response = client.evaluate(expression)

    val body = response.bodyAsText()
    assertEquals(HttpStatusCode.BadRequest, response.status, body)
    assertTrue(body.contains("OperationOutcome"), body)
    assertTrue(body.contains(""""code":"processing""""), body)
    assertFalse(body.contains("Internal server error"), body)
  }

  /**
   * The `resource` parameter may be an extension carrying the serialized resource instead of an
   * inline `resource` — the route the lab uses for engines that accept only R4 input parameters.
   */
  @Test
  fun resourceMayArriveInAJsonValueExtension() = testApplication {
    application { module() }

    val response =
      client.post("/fhirpath-r4") {
        contentType(ContentType(ContentType.Application.TYPE, "fhir+json"))
        setBody(
          """
          {"resourceType":"Parameters","parameter":[
            {"name":"expression","valueString":"Patient.id"},
            {"name":"resource","extension":[
              {"url":"http://fhir.forms-lab.com/StructureDefinition/json-value",
               "valueString":"{\"resourceType\":\"Patient\",\"id\":\"from-extension\"}"}]}]}
          """
            .trimIndent()
        )
      }

    val body = response.bodyAsText()
    assertEquals(HttpStatusCode.OK, response.status, body)
    assertTrue(body.contains(""""valueString":"from-extension""""), body)
  }

  /**
   * The XML form of that extension is refused explicitly — this server advertises `supportsXML:
   * false`, and a bare "missing resource" would point at the wrong thing.
   */
  @Test
  fun xmlValueResourceIsRejectedExplicitly() = testApplication {
    application { module() }

    val response =
      client.post("/fhirpath-r4") {
        contentType(ContentType(ContentType.Application.TYPE, "fhir+json"))
        setBody(
          """
          {"resourceType":"Parameters","parameter":[
            {"name":"expression","valueString":"Patient.id"},
            {"name":"resource","extension":[
              {"url":"http://fhir.forms-lab.com/StructureDefinition/xml-value",
               "valueString":"<Patient/>"}]}]}
          """
            .trimIndent()
        )
      }

    val body = response.bodyAsText()
    assertEquals(HttpStatusCode.BadRequest, response.status, body)
    assertTrue(body.contains("xml-value extension"), body)
    assertFalse(body.contains("Missing required parameter"), body)
  }

  /** An undecodable `resource` is a request fault too, not a server error. */
  @Test
  fun undecodableResourceIsReportedAsBadRequest() = testApplication {
    application { module() }

    val response = client.evaluate("id", resource = """{"resourceType":"NotARealResource"}""")

    val body = response.bodyAsText()
    assertEquals(HttpStatusCode.BadRequest, response.status, body)
    assertTrue(body.contains("Invalid 'resource'"), body)
  }

  /** Serializers routinely drop empty values, so an empty string is flagged by part name. */
  @Test
  fun emptyStringResultIsNamedEmptyString() = testApplication {
    application { module() }

    val body = client.evaluate("''").bodyAsText()

    assertTrue(body.contains(""""name":"empty-string""""), body)
  }

  /** Datatype part names use the FHIR type name; the spec's examples are `HumanName`, `Patient`. */
  @Test
  fun complexTypeResultUsesTheFhirTypeName() = testApplication {
    application { module() }

    val body = client.evaluate("name.first()").bodyAsText()

    assertTrue(body.contains(""""name":"HumanName""""), body)
  }

  /** Backbone elements have no FHIR type of their own, so they are qualified by their parent. */
  @Test
  fun backboneElementResultIsQualifiedByItsParent() = testApplication {
    application { module() }

    val body = client.evaluate("contact.first()").bodyAsText()

    assertTrue(body.contains(""""name":"Patient#Contact""""), body)
    assertTrue(body.contains("StructureDefinition/json-value"), body)
  }

  /** A resource is representable directly; the json-value extension is for what is not. */
  @Test
  fun resourceResultUsesTheResourceProperty() = testApplication {
    application { module() }

    val body = client.evaluate("%resource").bodyAsText()

    assertTrue(body.contains(""""name":"Patient","resource":{"resourceType":"Patient""""), body)
    assertFalse(body.contains("StructureDefinition/json-value"), body)
  }

  /** UCUM units carry the quotes of the expression literal they came from; a unit does not. */
  @Test
  fun ucumQuantityUnitDropsTheLiteralQuotes() = testApplication {
    application { module() }

    val body = client.evaluate("1 'mg'").bodyAsText()

    assertTrue(body.contains(""""name":"Quantity""""), body)
    assertTrue(body.contains(""""unit":"mg""""), body)
  }

  /** Calendar durations arrive unquoted and must survive that stripping untouched. */
  @Test
  fun calendarDurationQuantityUnitIsUnchanged() = testApplication {
    application { module() }

    val body = client.evaluate("1 year").bodyAsText()

    assertTrue(body.contains(""""unit":"year""""), body)
  }

  /** A UCUM quantity is identified by `system` and `code`; `unit` alone is a display string. */
  @Test
  fun ucumQuantityCarriesSystemAndCode() = testApplication {
    application { module() }

    val body = client.evaluate("1 'mg'").bodyAsText()

    assertTrue(body.contains(""""system":"http://unitsofmeasure.org""""), body)
    assertTrue(body.contains(""""code":"mg""""), body)
  }

  /** A calendar duration is not a UCUM unit, so it gets no code and no system. */
  @Test
  fun calendarDurationQuantityHasNoUcumCode() = testApplication {
    application { module() }

    val body = client.evaluate("1 year").bodyAsText()

    assertFalse(body.contains("unitsofmeasure.org"), body)
  }

  /**
   * The context description is the path as evaluated. A context expression that already names the
   * resource type must not be prefixed with it again.
   */
  @Test
  fun contextLabelDoesNotRepeatTheResourceType() = testApplication {
    application { module() }

    val body = client.evaluate("use", context = "Patient.name").bodyAsText()

    assertTrue(body.contains(""""valueString":"Patient.name[0]""""), body)
    assertFalse(body.contains("Patient.Patient.name"), body)
  }

  /** A context expression that omits the resource type is still labelled with the full path. */
  @Test
  fun contextLabelIsPrefixedWithTheResourceType() = testApplication {
    application { module() }

    val body = client.evaluate("use", context = "name").bodyAsText()

    assertTrue(body.contains(""""valueString":"Patient.name[0]""""), body)
  }

  /** Traced values carry the path they came from, which the lab links back into the resource. */
  @Test
  fun tracedValuesCarryTheirResourcePath() = testApplication {
    application { module() }

    val body = client.evaluate("name.given.trace('trc')").bodyAsText()

    assertTrue(body.contains(""""name":"trace","valueString":"trc""""), body)
    assertTrue(body.contains("StructureDefinition/resource-path"), body)
    assertTrue(body.contains(""""valueString":"Patient.name.given[0]""""), body)
  }

  /**
   * Under a context expression the engine reports trace paths relative to the context item, which
   * the lab cannot resolve. They are re-rooted onto the resource.
   */
  @Test
  fun tracedResourcePathsAreRootedAtTheResource() = testApplication {
    application { module() }

    val body = client.evaluate("given.trace('trc')", context = "name").bodyAsText()

    assertTrue(body.contains(""""valueString":"Patient.name[0].given[0]""""), body)
    assertFalse(body.contains(""""valueString":"HumanName."""), body)
  }

  /** Variables are echoed in their own `value[x]`, not flattened to a string. */
  @Test
  fun variablesAreEchoedInTheirOwnValueType() = testApplication {
    application { module() }

    val body =
      client
        .post("/fhirpath-r4") {
          contentType(ContentType(ContentType.Application.TYPE, "fhir+json"))
          setBody(
            """
            {"resourceType":"Parameters","parameter":[
              {"name":"expression","valueString":"%n"},
              {"name":"variables","part":[{"name":"n","valueInteger":5}]},
              {"name":"resource","resource":$patient}]}
            """
              .trimIndent()
          )
        }
        .bodyAsText()

    assertTrue(body.contains(""""name":"n","valueInteger":5"""), body)
    assertFalse(body.contains(""""name":"n","valueString":"5""""), body)
  }

  /**
   * The terminology server is echoed back, even though the engine has nothing to do with it yet.
   */
  @Test
  fun terminologyServerIsEchoed() = testApplication {
    application { module() }

    val body =
      client
        .post("/fhirpath-r4") {
          contentType(ContentType(ContentType.Application.TYPE, "fhir+json"))
          setBody(
            """
            {"resourceType":"Parameters","parameter":[
              {"name":"expression","valueString":"id"},
              {"name":"terminologyserver","valueString":"https://tx.fhir.org/r4"},
              {"name":"resource","resource":$patient}]}
            """
              .trimIndent()
          )
        }
        .bodyAsText()

    assertTrue(
      body.contains(""""name":"terminologyServerUrl","valueString":"https://tx.fhir.org/r4""""),
      body,
    )
  }
}
