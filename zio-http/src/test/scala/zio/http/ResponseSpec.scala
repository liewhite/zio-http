/*
 * Copyright 2021 - 2023 Sporta Technologies PVT LTD & the ZIO HTTP contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.http

import zio.test.Assertion._
import zio.test._

import zio.http.model._

object ResponseSpec extends ZIOSpecDefault {
  private val location: URL = URL.fromString("www.google.com").toOption.get

  def spec = suite("Response")(
    suite("redirect")(
      test("Temporary redirect should produce a response with a TEMPORARY_REDIRECT") {
        val x = Response.redirect(location)
        assertTrue(
          x.status == Status.TemporaryRedirect,
          x.header(Header.Location).contains(Header.Location(location)),
        )
      },
      test("Temporary redirect should produce a response with a location") {
        val x = Response.redirect(location)
        assertTrue(
          x.header(Header.Location).contains(Header.Location(location)),
        )
      },
      test("Permanent redirect should produce a response with a PERMANENT_REDIRECT") {
        val x = Response.redirect(location, isPermanent = true)
        assertTrue(x.status == Status.PermanentRedirect)
      },
      test("Permanent redirect should produce a response with a location") {
        val x = Response.redirect(location, isPermanent = true)
        assertTrue(
          x.headerOrFail(Header.Location).contains(Right(Header.Location(location))),
        )
      },
    ),
    suite("json")(
      test("Json should set content type to ApplicationJson") {
        val x = Response.json("""{"message": "Hello"}""")
        assertTrue(x.header(Header.ContentType).contains(Header.ContentType(MediaType.application.json)))
      },
    ),
    suite("toHttp")(
      test("should convert response to Http") {
        val ok   = Response.ok
        val http = ok.toHandler
        assertZIO(http.runZIO(()))(equalTo(ok))
      },
    ),
  )
}
