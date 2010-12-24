/*
 * Copyright 2010 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.ostrich

import java.net.URL
import scala.collection.immutable
import scala.io.Source
import com.twitter.json.Json
import com.twitter.util.Time
import com.twitter.util.TimeConversions._
import net.lag.extensions._
import org.specs._


object TimeSeriesCollectorSpec extends Specification {
  "TimeSeriesCollector" should {
    var collector: TimeSeriesCollector = null

    doBefore {
      Stats.clearAll()
      collector = new TimeSeriesCollector()
    }

    doAfter {
      collector.shutdown()
    }

    def getJson(port: Int, path: String) = {
      val url = new URL("http://localhost:%d%s".format(port, path))
      Json.parse(Source.fromURL(url).getLines.mkString("\n"))
    }

    "Stats.incr" in {
      Time.withCurrentTimeFrozen { tc =>
        Stats.incr("cats")
        Stats.incr("dogs", 3)
        collector.collector.periodic()
        tc.advance(1.minute)
        Stats.incr("dogs", 60000)
        collector.collector.periodic()

        val json = collector.get("counter:dogs", Nil)
        val data = Json.parse(json).asInstanceOf[Map[String, Seq[Seq[Number]]]]
        data("counter:dogs")(57) mustEqual List(2.minutes.ago.inSeconds, 0)
        data("counter:dogs")(58) mustEqual List(1.minute.ago.inSeconds, 3)
        data("counter:dogs")(59) mustEqual List(Time.now.inSeconds, 60000)
      }
    }

    "Stats.getCounter().update" in {
      Time.withCurrentTimeFrozen { tc =>
        Stats.getCounter("whales.tps").update(10)
        collector.collector.periodic()
        tc.advance(1.minute)
        Stats.getCounter("whales.tps").update(5)
        collector.collector.periodic()

        val json = collector.get("counter:whales.tps", Nil)
        val data = Json.parse(json).asInstanceOf[Map[String, Seq[Seq[Number]]]]
        data("counter:whales.tps")(57) mustEqual List(2.minutes.ago.inSeconds, 0)
        data("counter:whales.tps")(58) mustEqual List(1.minute.ago.inSeconds, 10)
        data("counter:whales.tps")(59) mustEqual List(Time.now.inSeconds, 5)
      }
    }

    "Stats.getCounter saved in variable" in {
      val whales = Stats.getCounter("whales.tps")
      Time.withCurrentTimeFrozen { tc =>
        whales.update(10)
        collector.collector.periodic()
        tc.advance(1.minute)
        whales.update(5)
        collector.collector.periodic()

        val json = collector.get("counter:whales.tps", Nil)
        val data = Json.parse(json).asInstanceOf[Map[String, Seq[Seq[Number]]]]
        data("counter:whales.tps")(57) mustEqual List(2.minutes.ago.inSeconds, 0)
        data("counter:whales.tps")(58) mustEqual List(1.minute.ago.inSeconds, 10)
        data("counter:whales.tps")(59) mustEqual List(Time.now.inSeconds, 5)
      }
    }

    "fetch json via http" in {
      Time.withCurrentTimeFrozen { tc =>
        Stats.incr("cats")
        Stats.incr("dogs", 3)
        collector.collector.periodic()
        tc.advance(1.minute)
        Stats.incr("dogs", 1)
        collector.collector.periodic()

        val service = new AdminHttpService(0, 20, new RuntimeEnvironment(getClass))
        collector.registerWith(service)
        service.start()
        val port = service.address.getPort
        try {
          val keys = getJson(port, "/graph_data").asInstanceOf[Map[String, Seq[String]]]
          keys("keys") mustContain "counter:dogs"
          keys("keys") mustContain "counter:cats"
          val data = getJson(port, "/graph_data/counter:dogs").asInstanceOf[Map[String, Seq[Seq[Number]]]]
          data("counter:dogs")(57) mustEqual List(2.minutes.ago.inSeconds, 0)
          data("counter:dogs")(58) mustEqual List(1.minute.ago.inSeconds, 3)
          data("counter:dogs")(59) mustEqual List(Time.now.inSeconds, 1)
        } finally {
          service.shutdown()
        }
      }
    }

    "fetch specific timing percentiles" in {
      Time.withCurrentTimeFrozen { tc =>
        Stats.addTiming("run", 5)
        Stats.addTiming("run", 10)
        Stats.addTiming("run", 15)
        Stats.addTiming("run", 20)
        collector.collector.periodic()

        val service = new AdminHttpService(0, 20, new RuntimeEnvironment(getClass))
        collector.registerWith(service)
        service.start()
        val port = service.address.getPort
        try {
          var data = getJson(port, "/graph_data/timing:run").asInstanceOf[Map[String, Seq[Seq[Number]]]]
          data("timing:run")(59) mustEqual List(Time.now.inSeconds, 6, 10, 17, 23, 23, 23, 23, 23)
          data = getJson(port, "/graph_data/timing:run?p=0,2").asInstanceOf[Map[String, Seq[Seq[Number]]]]
          data("timing:run")(59) mustEqual List(Time.now.inSeconds, 6, 17)
          data = getJson(port, "/graph_data/timing:run?p=1,7").asInstanceOf[Map[String, Seq[Seq[Number]]]]
          data("timing:run")(59) mustEqual List(Time.now.inSeconds, 10, 23)
        } finally {
          service.shutdown()
        }
      }
    }
  }
}
