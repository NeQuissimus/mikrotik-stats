import $ivy.`me.legrange:mikrotik:3.0.7`
import me.legrange.mikrotik._
import scala.collection.JavaConverters._
import scala.util.Try
import java.time.Duration
import java.math.BigInteger
import java.text.{DecimalFormat,DecimalFormatSymbols}
import java.util.Locale


object MetricFormat {
  val f = new DecimalFormat("0.######E0", DecimalFormatSymbols.getInstance(Locale.ROOT))
}

case class Metric(key: String, value: BigInteger, tags: Map[String, String]) {
  val tagsString = tags.map { case (k, v) => s"""${k}="${v}"""" }.mkString(",")
  val sciValue = MetricFormat.f.format(value)
  override def toString = s"""${key}{${tagsString}} ${sciValue}"""
}

object Keys {
  val prefix = "mikrotik_wireless"
  val signalToNoise = s"${prefix}_signaltonoise"
  val uptime = s"${prefix}_uptime_minutes"
  val bytes = s"${prefix}_bytes"

  val tagDirectionUp = Map("direction" -> "up")
  val tagDirectionDown = Map("direction" -> "down")
}

def textToDuration(uptimeStr: String) = {
  val dt = "P" + uptimeStr.replace("d", "dt").toUpperCase
  val pt = dt.indexOf('T') match {
    case -1 => dt.replace("P", "PT")
    case _ => dt
  }
  Try(Duration.parse(pt)).toOption
}

def format(kvps: scala.collection.mutable.Map[String, String]) = for {
  interface <- kvps.get("interface")
  ip <- kvps.get("last-ip")
  mac <- kvps.get("mac-address")
  bytes: String <- kvps.get("bytes")
  (bytesDownStr: String, bytesUpStr: String) <- Try { val a = bytes.split(","); (a.head, a.last) }.toOption
  bytesDown <- Try(new BigInteger(bytesDownStr)).toOption
  bytesUp <- Try(new BigInteger(bytesUpStr)).toOption
  uptimeStr <- kvps.get("uptime")
  uptime <- textToDuration(uptimeStr)
  signalToNoiseStr <- kvps.get("signal-to-noise")
  signalToNoise <- Try(new BigInteger(signalToNoiseStr)).toOption
  comment = kvps.get("comment")
} yield {
  val tags = Map("interface" -> interface, "ip" -> ip, "mac" -> mac) ++ comment.map(c => Map("comment" -> c)).getOrElse(Map.empty)

  List(
     Metric(Keys.signalToNoise, signalToNoise, tags),
     Metric(Keys.uptime, BigInteger.valueOf(uptime.toMinutes), tags),
     Metric(Keys.bytes, bytesDown, tags ++ Keys.tagDirectionDown),
     Metric(Keys.bytes, bytesUp, tags ++ Keys.tagDirectionUp)
  )
}

val con = ApiConnection.connect(sys.env("MIKROTIK_IP"))
con.login(sys.env("MIKROTIK_USER"), sys.env("MIKROTIK_PASSWORD"))
val clients = con.execute("/interface/wireless/registration-table/print without-paging stats").asScala.toList

clients.flatMap(map => format(map.asScala)).flatten.map(_.toString).foreach(println)

con.close()
