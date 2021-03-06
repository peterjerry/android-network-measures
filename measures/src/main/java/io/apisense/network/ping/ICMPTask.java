package io.apisense.network.ping;

import android.support.annotation.NonNull;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.apisense.network.Measurement;
import io.apisense.network.MeasurementError;

/**
 * Measurement class used to realise a Traceroute
 */
public class ICMPTask extends Measurement {
  public static final String TAG = "PING";

  private static final String REGEX_TTL_EXCEEDED = "icmp_seq=\\d+ Time to live exceeded";
  private static final String REGEX_SUCCESS = "icmp_seq=\\d+ ttl=\\d+ time=(\\d+.\\d+) ms";
  private static final String REGEX_RTT_SUCCESS = "(\\d+.\\d+)/(\\d+.\\d+)/(\\d+.\\d+)/(\\d+.\\d+) ms";

  /**
   * Template: From *ip*: icmp_seq=1 Time to live exceeded
   */
  private static final Pattern PING_RESPONSE_TTL_EXCEEDED_NO_HOSTNAME = Pattern.compile(
      "From ([\\d.]+): " + REGEX_TTL_EXCEEDED);

  /**
   * Template:
   * 64 bytes from *ip*: icmp_seq=1 ttl=*ttl* time=*latency* ms
   */
  private static final Pattern PING_RESPONSE_SUCCESS_NO_HOSTNAME = Pattern.compile(
      "\\d+ bytes from ([\\d.]+): " + REGEX_SUCCESS);

  /**
   * Template:
   * From *hostname* (*ip*): icmp_seq=1 Time to live exceeded
   */
  private static final Pattern PING_RESPONSE_TTL_EXCEEDED = Pattern.compile(
      "From ([\\w\\d\\-.]+) \\(([\\d.]+)\\): " + REGEX_TTL_EXCEEDED);

  /**
   * Template:
   * 64 bytes from *hostname* (*ip*): icmp_seq=1 ttl=*ttl* time=*latency* ms
   */
  private static final Pattern PING_RESPONSE_SUCCESS = Pattern.compile(
      "\\d+ bytes from ([\\w\\d\\-.]+) \\(([\\d.]+)\\): " + REGEX_SUCCESS);

  /**
   * Template:
   * round-trip min/avg/max/stddev = *rtt.min*\/*rtt.avg*\/*rtt.max*\/0.114 ms
   */
  private static final Pattern PING_RESPONSE_RTT_SUCCESS = Pattern.compile(
      "rtt min/avg/max/mdev = " + REGEX_RTT_SUCCESS);

  /**
   * This message is displayed when all packets are lost.
   *
   * Template:
   * 1 packets transmitted, 0 received, 100% packet loss, time 0ms
   */
  private static final Pattern PING_RESPONSE_TIMEOUT = Pattern.compile(
      "\\d+ packets transmitted, 0 received, 100% packet loss, time \\d+ms"
  );

  private ICMPConfig config;

  public ICMPTask(ICMPConfig config) {
    super(TAG);
    this.config = config;
  }

  /**
   * *Synchronous* Ping request with a specific ttl.
   *
   * @param url The url to set on the command request.
   * @param ttl The TTL to set on the command request.
   * @return The ping command to execute.
   */
  private static String generatePingCommand(String url, int ttl) {
    String format = "ping -c 1 -t %d ";
    return String.format(Locale.US, format, ttl) + url;
  }

  /**
   * Actual generatePingCommand request and response parsing.
   *
   * @param command The command to execute for this ping.
   * @return The {@link ICMPResult} value.
   * @throws ICMPTask.PINGException If the ping execution fails.
   * @throws IOException
   * @throws InterruptedException
   */
  private ICMPResult launchPingCommand(String command) throws PINGException, IOException, InterruptedException {
    Log.d(TAG, "Will launch : " + command);
    long startTime = System.currentTimeMillis();
    Process p = Runtime.getRuntime().exec(command);

    BufferedReader stdin = new BufferedReader(new InputStreamReader(p.getInputStream()));
    if (p.waitFor() >= 2) {
      throw new ICMPTask.PINGException(p.getErrorStream());
    } else {
      return parsePingResponse(stdin, startTime);
    }
  }

  /**
   * Retrieve every possible information about the Ping.
   *
   * @param stdin       The ping output.
   * @param startTimeMs The task start timestamp in millisecond.
   * @return The built result from output.
   * @throws IOException
   */
  private ICMPResult parsePingResponse(BufferedReader stdin, long startTimeMs) throws IOException, PINGException {
    String hostname = null;
    String ip = null;
    Rtt rtt;
    long endTime = System.currentTimeMillis();
    long latency = endTime - startTimeMs;
    int currentTtl = config.getTtl();

    String line;
    Matcher matcher;

    while ((line = stdin.readLine()) != null) {
      Log.d(TAG, "Parsing line : " + line);

      matcher = PING_RESPONSE_TTL_EXCEEDED.matcher(line);

      if (matcher.matches()) {
        hostname = matcher.group(1);
        ip = matcher.group(2);
        return new ICMPResult(startTimeMs, endTime, config, hostname, ip, latency, currentTtl, null);
      }

      matcher = PING_RESPONSE_TTL_EXCEEDED_NO_HOSTNAME.matcher(line);

      if (matcher.matches()) {
        ip = matcher.group(1);
        return new ICMPResult(startTimeMs, endTime, config, null, ip, latency, currentTtl, null);
      }

      matcher = PING_RESPONSE_SUCCESS.matcher(line);

      if (matcher.matches()) {
        hostname = matcher.group(1);
        ip = matcher.group(2);
        latency = Float.valueOf(matcher.group(3)).longValue(); // milliseconds
      }

      matcher = PING_RESPONSE_SUCCESS_NO_HOSTNAME.matcher(line);

      if (matcher.matches()) {
        ip = matcher.group(1);
        latency = Float.valueOf(matcher.group(2)).longValue(); // milliseconds
      }

      matcher = PING_RESPONSE_RTT_SUCCESS.matcher(line);

      if (matcher.matches()) {
        float min = Float.valueOf(matcher.group(1));
        float avg = Float.valueOf(matcher.group(2));
        float max = Float.valueOf(matcher.group(3));
        float mdev = Float.valueOf(matcher.group(4));

        rtt = new Rtt(min, avg, max, mdev);

        return new ICMPResult(startTimeMs, endTime, config, hostname, ip, latency, currentTtl, rtt);
      }

      matcher = PING_RESPONSE_TIMEOUT.matcher(line);

      if (matcher.matches()) {
        throw new PINGException("Packet is lost");
      }
    }

    throw new PINGException("Could not parse response");
  }

  @Override
  @NonNull
  public ICMPResult execute() throws MeasurementError {
    try {
      String command = generatePingCommand(config.getUrl(), config.getTtl());
      return launchPingCommand(command);
    } catch (PINGException | IOException | InterruptedException e) {
      throw new MeasurementError(taskName, e);
    }
  }

  /**
   * Exception thrown from a PING task
   */
  private static class PINGException extends Exception {
    PINGException(InputStream errorStream) {
      super(buildErrorMessage(errorStream));
    }

    PINGException(String message) {
      super(message);
    }

    @NonNull
    private static String buildErrorMessage(InputStream errorStream) {
      BufferedReader stderr = new BufferedReader(new InputStreamReader(errorStream));
      String nextLine = "";
      String message = "";
      while (nextLine != null) {
        message += nextLine;
        try {
          nextLine = stderr.readLine();
        } catch (IOException e) {
          Log.w(TAG, "Error message creation interupted.", e);
          break; // Stop reading and returns what we had until now.
        }
      }
      return message;
    }
  }
}
