package eu.wohlben.qits.dns.wire;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.OPTRecord;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.TextParseException;

/**
 * The client half of the wire suite: plain {@link DatagramSocket} and {@link Socket}, with dnsjava
 * used only to build the query bytes and parse the reply bytes.
 *
 * <p><b>Deliberately not {@code SimpleResolver}</b>, which §9 of the design suggests. dnsjava's
 * resolver stack is precisely what the native build severs — {@code application.properties} strips
 * the {@code InetAddressResolverProvider} service file that makes {@code Lookup} and {@code
 * ResolverConfig} reachable, because they pull {@code android.net.ConnectivityManager} and {@code
 * com.sun.jna.Pointer} in behind them and because {@code Lookup}'s class initializer bakes the
 * BUILD MACHINE's {@code /etc/resolv.conf} into the binary. Keeping those classes out of the TEST
 * sources too is what stops somebody copying an import from here into {@code src/main} and
 * discovering the problem at native-build time, three commits later. It also costs nothing: a query
 * is a {@link Message} and a datagram, and writing both by hand is what makes the framing test able
 * to look at the length prefix at all.
 *
 * <p>Every socket here is short-lived and explicitly timed out. A hang in a socket suite is a build
 * that parks rather than a build that fails, which is the worst of the two.
 *
 * <p>Public, and used from the {@code api} suite as well as this one — {@code
 * DnsWriteThenResolveTest} and {@code DnsPackagedSurfaceIT} write a record over HTTP and then ask
 * for it over real UDP, which is the loop this service exists to close. It stays in the {@code
 * wire} package because that is where the knowledge it encodes lives.
 */
public final class WireClient {

  /**
   * Long enough for a loopback round trip by three orders of magnitude, short enough to fail fast.
   */
  public static final int TIMEOUT_MILLIS = 2000;

  private WireClient() {}

  /** A query with an explicit id, so a test can assert the response carried it back. */
  public static Message query(int id, String qname, int qtype) throws TextParseException {
    // new Message(int), never new Message(): the no-arg constructor reads Header's static
    // SecureRandom to invent an id. Harmless in a test, but an explicit id is what lets the round
    // trip assert on it, and this is the same constructor src/main uses for the same class of
    // reason.
    Message message = new Message(id);
    message.getHeader().setFlag(Flags.RD);
    message.addRecord(Record.newRecord(Name.fromString(qname), qtype, DClass.IN), Section.QUESTION);
    return message;
  }

  /** The same, advertising an EDNS0 UDP payload size. */
  public static Message queryWithEdns(int id, String qname, int qtype, int payloadSize)
      throws TextParseException {
    Message message = query(id, qname, qtype);
    message.addRecord(new OPTRecord(payloadSize, 0, 0), Section.ADDITIONAL);
    return message;
  }

  /**
   * Query bytes. {@code toWire(int)} rather than the no-arg form, which dnsjava's own javadoc says
   * not to transmit — it skips OPT handling, so an EDNS0 query built through it would not be one.
   */
  public static byte[] wire(Message message) {
    return message.toWire(65535);
  }

  /**
   * One UDP exchange, returning the raw response bytes. Sizes matter here, so bytes, not Message.
   */
  public static byte[] udp(int port, Message query) throws IOException {
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.setSoTimeout(TIMEOUT_MILLIS);
      send(socket, port, wire(query));
      return receive(socket);
    }
  }

  /**
   * Sends {@code request} and asserts nothing comes back within the timeout, by letting the receive
   * throw {@link java.net.SocketTimeoutException}. Returns true when the datagram was dropped.
   */
  public static boolean udpSilent(int port, byte[] request) throws IOException {
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.setSoTimeout(TIMEOUT_MILLIS);
      send(socket, port, request);
      try {
        receive(socket);
        return false;
      } catch (java.net.SocketTimeoutException expected) {
        return true;
      }
    }
  }

  private static void send(DatagramSocket socket, int port, byte[] request) throws IOException {
    socket.send(
        new DatagramPacket(request, request.length, InetAddress.getLoopbackAddress(), port));
  }

  private static byte[] receive(DatagramSocket socket) throws IOException {
    // 65535 because the point of several of these tests is exactly how big the answer is: a buffer
    // sized to what we expect would silently truncate the evidence for a budget the server got
    // wrong.
    byte[] buffer = new byte[65535];
    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
    socket.receive(packet);
    return Arrays.copyOf(buffer, packet.getLength());
  }

  public static Message parse(byte[] wire) throws IOException {
    return new Message(wire);
  }

  /**
   * A TCP connection that outlives one query, because RFC 7766 says a resolver may reuse one and
   * the framing test says so twice.
   */
  public static final class Tcp implements Closeable {

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int lastLengthPrefix = -1;

    public Tcp(int port) throws IOException {
      socket = new Socket();
      socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), TIMEOUT_MILLIS);
      socket.setSoTimeout(TIMEOUT_MILLIS);
      in = new DataInputStream(socket.getInputStream());
      out = socket.getOutputStream();
    }

    /** Writes the RFC 1035 §4.2.2 two-byte big-endian length prefix, then the message. */
    public byte[] exchange(Message query) throws IOException {
      byte[] request = wire(query);
      out.write(new byte[] {(byte) (request.length >> 8), (byte) request.length});
      out.write(request);
      out.flush();
      lastLengthPrefix = in.readUnsignedShort();
      byte[] response = new byte[lastLengthPrefix];
      in.readFully(response);
      return response;
    }

    /**
     * The prefix the server sent on the last exchange, so a test can assert it framed correctly.
     */
    public int lastLengthPrefix() {
      return lastLengthPrefix;
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }
  }
}
