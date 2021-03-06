package lila.security

import play.api.libs.ws.WS
import play.api.Play.current
import scala.concurrent.duration._

import lila.common.IpAddress

final class IpIntel(asyncCache: lila.memo.AsyncCache.Builder, lichessEmail: String) {

  def apply(ip: IpAddress): Fu[Int] = failable(ip) recover {
    case e: Exception =>
      logger.warn(s"IpIntel $ip", e)
      0
  }

  def failable(ip: IpAddress): Fu[Int] = cache get ip

  private val cache = asyncCache.multi[IpAddress, Int](
    name = "ipIntel",
    f = ip => {
      val url = s"http://check.getipintel.net/check.php?ip=$ip&contact=$lichessEmail"
      WS.url(url).get().map(_.body).mon(_.security.proxy.request.time).flatMap { str =>
        parseFloatOption(str).fold[Fu[Int]](fufail(s"Invalid ratio ${str.take(140)}")) { ratio =>
          if (ratio < 0) fufail(s"Error code $ratio")
          else fuccess((ratio * 100).toInt)
        }
      }.addEffects(
        fail = _ => lila.mon.security.proxy.request.failure(),
        succ = percent => {
          lila.mon.security.proxy.percent(percent max 0)
          lila.mon.security.proxy.request.success()
        }
      )
    },
    expireAfter = _.ExpireAfterAccess(3 days)
  )
}
