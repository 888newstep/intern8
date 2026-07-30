package vip.xiaozhao.intern.baseUtil.controller.util;

import org.apache.commons.lang3.StringUtils;

import jakarta.servlet.http.HttpServletRequest;

public class IPUtil {

    /**
     * 获取用户IP
     *
     * @return
     */
    public static String getRequestIP(HttpServletRequest httpRequest) {
        String ipForwarded = httpRequest.getHeader("x-forwarded-for");
        if (ipForwarded == null) {
            ipForwarded = httpRequest.getRemoteAddr();
        } else {
            String ips[] = ipForwarded.split(",");
            ipForwarded = ips[ips.length - 1].trim();
        }
        if (StringUtils.equals(ipForwarded, "0:0:0:0:0:0:0:1"))
            return "127.0.0.1";
        return ipForwarded;
    }

}
