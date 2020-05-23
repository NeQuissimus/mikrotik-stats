import $ivy.`me.legrange:mikrotik:3.0.7`
import me.legrange.mikrotik._
import scala.collection.JavaConverters._
import scala.collection.mutable
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
  val prefix = "nequi_mikrotik"
  val wirelessPrefix = s"${prefix}_wireless"
  val dnsCache = s"${prefix}_dns_cache"
  val dhcpLeases = s"${prefix}_dhcp_leases"
  val signalToNoise = s"${wirelessPrefix}_signaltonoise"
  val uptime = s"${wirelessPrefix}_uptime_minutes"
  val bytes = s"${wirelessPrefix}_bytes"

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

def formatRegistrationTable(device: String, kvps: mutable.Map[String, String]) = for {
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
  val tags = Map("device" -> device, "interface" -> interface, "ip" -> ip, "mac" -> mac) ++ comment.map(c => Map("comment" -> c)).getOrElse(Map.empty)

  List(
     Metric(Keys.signalToNoise, signalToNoise, tags),
     Metric(Keys.uptime, BigInteger.valueOf(uptime.toMinutes), tags),
     Metric(Keys.bytes, bytesDown, tags ++ Keys.tagDirectionDown),
     Metric(Keys.bytes, bytesUp, tags ++ Keys.tagDirectionUp)
  )
}

def formatDhcpLeases(device: String, kvps: mutable.Map[String, String]) = for {
  mac <- kvps.get("active-mac-address")
  address <- kvps.get("active-address")
  dhcpServer <- kvps.get("active-server")
  hostname = kvps.get("host-name").getOrElse("")
  dynamic <- kvps.get("dynamic")
  comment = kvps.get("comment")
} yield {
  val tags = Map("device" -> device, "mac" -> mac, "address" -> address, "dhcp_server" -> dhcpServer, "hostname" -> hostname, "dynamic" -> dynamic) ++ comment.map(c => Map("comment" -> c)).getOrElse(Map.empty)

  Metric(Keys.dhcpLeases, BigInteger.valueOf(1L), tags)
}

def formatDnsCache(device: String, kvps: mutable.Map[String, String]) = for {
  address <- kvps.get("address")
  static <- kvps.get("static")
  name <- kvps.get("name")
  ttlStr <- kvps.get("ttl")
  ttl <- textToDuration(ttlStr)
} yield {
  val tags = Map("device" -> device, "address" -> address, "static" -> static, "name" -> name)

  Metric(Keys.dnsCache, BigInteger.valueOf(ttl.getSeconds), tags)
}

val devices = sys.env("MIKROTIK_IPS").split(",").map(_.trim)

devices.foreach { ip =>
  val con = ApiConnection.connect(ip)
  con.login(sys.env("MIKROTIK_USER"), sys.env("MIKROTIK_PASSWORD"))

  val registrations = con.execute("/interface/wireless/registration-table/print without-paging stats").asScala.toList
  registrations.flatMap(map => formatRegistrationTable(ip, map.asScala)).flatten.map(_.toString).foreach(println)

  val dhcpLeases = con.execute("/ip/dhcp-server/lease/print where status=bound").asScala.toList
  dhcpLeases.flatMap(map => formatDhcpLeases(ip, map.asScala)).map(_.toString).foreach(println)

  val dnsCache = con.execute("/ip/dns/cache/print without-paging").asScala.toList
  dnsCache.flatMap(map => formatDnsCache(ip, map.asScala)).map(_.toString).foreach(println)

  con.close()
}
