package com.phraselog.usage.service;

import com.phraselog.common.web.ApiErrorException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ClientIpResolver {

  public static final String HEADER = "X-Client-IP";

  private static final Pattern IPV4 =
      Pattern.compile(
          "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

  public String resolveRequired(String headerValue) {
    if (!StringUtils.hasText(headerValue)) {
      throw validationFailed();
    }

    String candidate = headerValue.trim();
    if (candidate.contains(",") || candidate.contains("[") || candidate.contains("]")) {
      throw validationFailed();
    }

    if (IPV4.matcher(candidate).matches()) {
      return parse(candidate, Inet4Address.class);
    }

    if (candidate.contains(":") && !candidate.contains("%")) {
      return parse(candidate, Inet6Address.class);
    }

    throw validationFailed();
  }

  private static String parse(String candidate, Class<? extends InetAddress> expectedType) {
    try {
      InetAddress address = InetAddress.getByName(candidate);
      if (!expectedType.isInstance(address)) {
        throw validationFailed();
      }
      return address.getHostAddress();
    } catch (UnknownHostException e) {
      throw validationFailed();
    }
  }

  private static ApiErrorException validationFailed() {
    return new ApiErrorException(
        HttpStatus.BAD_REQUEST,
        "validation_failed",
        "?낅젰媛믪쓣 ?ㅼ떆 ?뺤씤??二쇱꽭??",
        "Validate X-Client-IP before calling the analysis service.",
        false);
  }
}
