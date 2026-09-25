/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.service.rules.sieve;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import javax.mail.PasswordAuthentication;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.exoplatform.emailConnector.service.rules.sieve.ManageSieveException.Kind;

/**
 * The subset of ManageSieve (RFC 5804) eXo needs to publish its own Sieve script: one
 * connection per operation, {@code STARTTLS} mandatory, SASL {@code PLAIN} only.
 * <p>
 * The connection sequence is fixed and not negotiable by the server: read the greeting,
 * refuse a server that does not offer {@code STARTTLS}, upgrade, verify the certificate
 * <b>and</b> the host name, then read the capabilities the server re-issues after TLS
 * (RFC 5804 §2.2) and forget the clear-text ones. Nothing the server said before TLS is
 * trusted, and bytes it pushed ahead of the TLS handshake are refused rather than read
 * as if they came through the tunnel.
 * <p>
 * Every read is bounded: a socket timeout on every read and a deadline on every
 * operation (greeting to capabilities, or one command to its final line), so a server
 * trickling bytes cannot hold the caller's thread; and a cap on each literal, quoted
 * string and atom, on the tokens of a line, the lines of a response and the octets of a
 * whole response, so it cannot exhaust memory either. The server's own text reaches an
 * exception message only on one line and truncated.
 * <p>
 * Not thread-safe: one instance is one conversation, owned by one caller.
 */
public final class ManageSieveClient implements AutoCloseable {

  /** The IANA port of ManageSieve. */
  public static final int     DEFAULT_PORT                   = 4190;

  /** How long a TCP connect may take, the IMAP store's value. */
  public static final int     DEFAULT_CONNECT_TIMEOUT_MILLIS = 15000;

  /** How long any single read may block, the IMAP store's value. */
  public static final int     DEFAULT_READ_TIMEOUT_MILLIS    = 30000;

  /** How long one operation may take in all, however the server paces its bytes. */
  public static final int     DEFAULT_OPERATION_TIMEOUT_MILLIS = 60000;

  /** The largest literal the client accepts from the server; Sieve scripts are kilobytes. */
  static final int            MAX_LITERAL_OCTETS             = 1024 * 1024;

  /** RFC 5804 §4: a quoted string holds at most 1024 octets; longer values go as literals. */
  static final int            MAX_QUOTED_OCTETS              = 1024;

  /**
   * The longest quoted string accepted from the server: RFC 5804's 1024 octets with room
   * for a server that sends a long capability line quoted rather than as a literal.
   */
  static final int            MAX_SERVER_QUOTED_OCTETS       = 4 * MAX_QUOTED_OCTETS;

  /** The most tokens one response line may carry. */
  static final int            MAX_TOKENS_PER_LINE            = 64;

  /** The most octets one whole response may carry: one full literal and its framing. */
  static final int            MAX_RESPONSE_OCTETS            = 2 * 1024 * 1024;

  /** The longest server text kept in an exception message. */
  static final int            MAX_MESSAGE_CHARS              = 200;

  /** The longest atom the client accepts. */
  static final int            MAX_ATOM_OCTETS                = 1024;

  /** The most data lines one response may carry. */
  static final int            MAX_RESPONSE_LINES             = 10000;

  /** The only SASL mechanism the client speaks. */
  static final String         SASL_PLAIN                     = "PLAIN";

  private static final byte[] CRLF                           = { '\r', '\n' };

  /** The host the client connected to, also the name the certificate must match. */
  private final String        host;

  /** The port the client connected to. */
  private final int           port;

  /** The current socket: the clear one until STARTTLS, the TLS one after. */
  private Socket              socket;

  /** The input of the current socket. */
  private InputStream         in;

  /** The output of the current socket. */
  private OutputStream        out;

  /** A byte read ahead by the tokenizer and not consumed yet, -1 when none. */
  private int                 pushedBack                     = -1;

  /** The capabilities read after TLS. */
  private ManageSieveCapabilities capabilities;

  /** The time budget of one operation, in milliseconds. */
  private int                 operationTimeoutMillis         = DEFAULT_OPERATION_TIMEOUT_MILLIS;

  /** When the current operation must be over, from {@link System#nanoTime()}. */
  private long                deadlineNanos;

  /** The octets read so far in the current response. */
  private long                responseOctets;

  /**
   * A client bound to an endpoint, not connected yet.
   *
   * @param host the server host
   * @param port the server port
   */
  private ManageSieveClient(String host, int port) {
    this.host = host;
    this.port = port;
  }

  /**
   * Connects, upgrades to TLS and reads the server's capabilities. The returned client
   * is not authenticated yet.
   *
   * @param host the server host, also the name its certificate must carry
   * @param port the server port
   * @param tlsSocketFactory the factory that layers TLS over the connected socket
   * @param connectTimeoutMillis the TCP connect timeout
   * @param readTimeoutMillis the timeout of every read
   * @return the connected client, over TLS
   * @throws ManageSieveException {@link Kind#UNAVAILABLE} when the server cannot be
   *           reached or the TLS handshake fails, {@link Kind#TLS_REQUIRED} when it does
   *           not offer {@code STARTTLS}, {@link Kind#PROTOCOL} when its answers are
   *           malformed
   */
  public static ManageSieveClient connect(String host,
                                          int port,
                                          SSLSocketFactory tlsSocketFactory,
                                          int connectTimeoutMillis,
                                          int readTimeoutMillis) throws ManageSieveException {
    return connect(host, port, tlsSocketFactory, connectTimeoutMillis, readTimeoutMillis, DEFAULT_OPERATION_TIMEOUT_MILLIS);
  }

  /**
   * Connects, upgrades to TLS and reads the server's capabilities, with an explicit
   * budget per operation. The returned client is not authenticated yet.
   *
   * @param host the server host, also the name its certificate must carry
   * @param port the server port
   * @param tlsSocketFactory the factory that layers TLS over the connected socket
   * @param connectTimeoutMillis the TCP connect timeout
   * @param readTimeoutMillis the timeout of every read
   * @param operationTimeoutMillis the time budget of every operation, the connection
   *          sequence included
   * @return the connected client, over TLS
   * @throws ManageSieveException as {@link #connect(String, int, SSLSocketFactory, int, int)}
   */
  public static ManageSieveClient connect(String host,
                                          int port,
                                          SSLSocketFactory tlsSocketFactory,
                                          int connectTimeoutMillis,
                                          int readTimeoutMillis,
                                          int operationTimeoutMillis) throws ManageSieveException {
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("A ManageSieve host is required");
    }
    if (tlsSocketFactory == null) {
      throw new IllegalArgumentException("A TLS socket factory is required");
    }
    ManageSieveClient client = new ManageSieveClient(host, port);
    client.operationTimeoutMillis = operationTimeoutMillis;
    try {
      client.open(tlsSocketFactory, connectTimeoutMillis, readTimeoutMillis);
      return client;
    } catch (ManageSieveException | RuntimeException e) {
      client.close();
      throw e;
    }
  }

  /**
   * The capabilities the server advertised after TLS.
   *
   * @return the capabilities, never null on a connected client
   */
  public ManageSieveCapabilities getCapabilities() {
    return capabilities;
  }

  /**
   * Authenticates with SASL {@code PLAIN} (RFC 4616), no authorization identity: the
   * session acts as the account whose credentials these are, and as nobody else.
   *
   * The credentials are the caller's to resolve — the IMAP channel's material from the
   * connector credentials contract — so the client depends on no session type and never
   * reads a stored password.
   *
   * @param credentials the login, and the password or the session id a provider
   *          presents as one
   * @throws ManageSieveException {@link Kind#UNSUPPORTED_MECHANISM} when the server does
   *           not offer {@code PLAIN} after TLS, {@link Kind#AUTHENTICATION} when it
   *           refuses the credentials or there are none
   */
  public void authenticatePlain(PasswordAuthentication credentials) throws ManageSieveException {
    if (!capabilities.supportsSasl(SASL_PLAIN)) {
      throw new ManageSieveException(Kind.UNSUPPORTED_MECHANISM,
                                     "The ManageSieve server does not offer SASL PLAIN after STARTTLS");
    }
    if (credentials == null || credentials.getUserName() == null || credentials.getPassword() == null) {
      throw new ManageSieveException(Kind.NO_CREDENTIALS, "No credentials to authenticate with");
    }
    byte[] loginBytes = credentials.getUserName().getBytes(StandardCharsets.UTF_8);
    byte[] passwordBytes = credentials.getPassword().getBytes(StandardCharsets.UTF_8);
    byte[] message = new byte[loginBytes.length + passwordBytes.length + 2];
    System.arraycopy(loginBytes, 0, message, 1, loginBytes.length);
    System.arraycopy(passwordBytes, 0, message, loginBytes.length + 2, passwordBytes.length);
    String initialResponse = Base64.getEncoder().encodeToString(message);
    Response response = command("AUTHENTICATE", "AUTHENTICATE " + string("PLAIN") + " " + string(initialResponse));
    if (response.status == Status.NO && "TRYLATER".equals(response.code)) {
      throw new ManageSieveException(Kind.UNAVAILABLE,
                                     response.code,
                                     "The ManageSieve server asked to authenticate later",
                                     null);
    }
    if (response.status == Status.NO) {
      throw new ManageSieveException(Kind.AUTHENTICATION,
                                     response.code,
                                     "The ManageSieve server refused the credentials",
                                     null);
    }
    requireOk(response, "AUTHENTICATE");
  }

  /**
   * Lists the account's scripts.
   *
   * @return the scripts, in the server's order
   * @throws ManageSieveException when the server refuses or breaks the protocol
   */
  public List<SieveScriptInfo> listScripts() throws ManageSieveException {
    Response response = command("LISTSCRIPTS", "LISTSCRIPTS");
    requireOk(response, "LISTSCRIPTS");
    List<SieveScriptInfo> scripts = new ArrayList<>();
    for (List<Token> line : response.lines) {
      if (line.isEmpty() || line.get(0).type != TokenType.STRING) {
        throw new ManageSieveException(Kind.PROTOCOL, "Malformed LISTSCRIPTS line");
      }
      boolean active = line.size() > 1 && line.get(1).type == TokenType.ATOM
          && "ACTIVE".equalsIgnoreCase(line.get(1).value);
      scripts.add(new SieveScriptInfo(line.get(0).value, active));
    }
    return scripts;
  }

  /**
   * Reads a script's text, as stored: comments included, byte for byte.
   *
   * @param name the script name
   * @return the script text
   * @throws ManageSieveException {@link Kind#REFUSED} with {@code NONEXISTENT} when the
   *           script does not exist
   */
  public String getScript(String name) throws ManageSieveException {
    Response response = command("GETSCRIPT", "GETSCRIPT " + string(name));
    requireOk(response, "GETSCRIPT");
    if (response.lines.size() != 1 || response.lines.get(0).size() != 1
        || response.lines.get(0).get(0).type != TokenType.STRING) {
      throw new ManageSieveException(Kind.PROTOCOL, "Malformed GETSCRIPT answer");
    }
    return response.lines.get(0).get(0).value;
  }

  /**
   * Stores a script under a name, replacing a script of that name. Storing does not
   * activate it.
   *
   * @param name the script name
   * @param content the script text
   * @throws ManageSieveException {@link Kind#REFUSED} when the server refuses it (a
   *           compile error, a quota)
   */
  public void putScript(String name, String content) throws ManageSieveException {
    requireOk(command("PUTSCRIPT", "PUTSCRIPT " + string(name) + " " + literal(content)), "PUTSCRIPT");
  }

  /**
   * Asks the server to compile a script without storing it, when it offers
   * {@code CHECKSCRIPT}.
   *
   * @param content the script text
   * @return true when the server checked it, false when it offers no
   *         {@code CHECKSCRIPT} and nothing was sent
   * @throws ManageSieveException {@link Kind#REFUSED} when the script does not compile
   */
  public boolean checkScript(String content) throws ManageSieveException {
    if (!capabilities.supportsCheckScript()) {
      return false;
    }
    requireOk(command("CHECKSCRIPT", "CHECKSCRIPT " + literal(content)), "CHECKSCRIPT");
    return true;
  }

  /**
   * Makes a script the account's active one, which deactivates the previous one.
   *
   * @param name the script name
   * @throws ManageSieveException when the server refuses
   */
  public void setActive(String name) throws ManageSieveException {
    requireOk(command("SETACTIVE", "SETACTIVE " + string(name)), "SETACTIVE");
  }

  /**
   * Deletes a script. The server refuses to delete the active one.
   *
   * @param name the script name
   * @throws ManageSieveException when the server refuses
   */
  public void deleteScript(String name) throws ManageSieveException {
    requireOk(command("DELETESCRIPT", "DELETESCRIPT " + string(name)), "DELETESCRIPT");
  }

  /**
   * Ends the conversation politely, then closes the connection whatever the server
   * answered.
   */
  public void logout() {
    try {
      if (socket != null && !socket.isClosed()) {
        write("LOGOUT");
        readResponse();
      }
    } catch (IOException | ManageSieveException | RuntimeException e) {
      // The connection is closed below either way.
    } finally {
      close();
    }
  }

  /**
   * Closes the connection without a {@code LOGOUT}.
   */
  @Override
  public void close() {
    if (socket != null) {
      try {
        socket.close();
      } catch (IOException e) {
        // Nothing left to release.
      }
    }
  }

  /**
   * Connects, reads the greeting, upgrades to TLS and reads the re-issued capabilities.
   *
   * @param tlsSocketFactory the TLS layer
   * @param connectTimeoutMillis the TCP connect timeout
   * @param readTimeoutMillis the read timeout
   * @throws ManageSieveException when any step fails
   */
  private void open(SSLSocketFactory tlsSocketFactory,
                    int connectTimeoutMillis,
                    int readTimeoutMillis) throws ManageSieveException {
    startOperation();
    try {
      Socket plain = new Socket();
      socket = plain;
      plain.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
      plain.setSoTimeout(readTimeoutMillis);
      bindStreams();
      Response greeting = readResponse();
      requireOk(greeting, "greeting");
      ManageSieveCapabilities clearText = capabilitiesOf(greeting);
      if (!clearText.starttls()) {
        throw new ManageSieveException(Kind.TLS_REQUIRED,
                                       "The ManageSieve server does not offer STARTTLS; eXo never talks to it in clear");
      }
      write("STARTTLS");
      requireOk(readResponse(), "STARTTLS");
      if (pushedBack >= 0 || in.available() > 0) {
        throw new ManageSieveException(Kind.PROTOCOL, "The ManageSieve server sent data ahead of the TLS handshake");
      }
      SSLSocket tls = (SSLSocket) tlsSocketFactory.createSocket(plain, host, port, true);
      SSLParameters parameters = tls.getSSLParameters();
      parameters.setEndpointIdentificationAlgorithm("HTTPS");
      tls.setSSLParameters(parameters);
      tls.setSoTimeout(readTimeoutMillis);
      socket = tls;
      tls.startHandshake();
      bindStreams();
      Response afterTls = readResponse();
      requireOk(afterTls, "capabilities after STARTTLS");
      capabilities = capabilitiesOf(afterTls);
    } catch (IOException e) {
      throw unavailable("connect", e);
    }
  }

  /**
   * Binds the buffered streams of the current socket.
   *
   * @throws IOException when the socket has no streams
   */
  private void bindStreams() throws IOException {
    in = new BufferedInputStream(socket.getInputStream());
    out = new BufferedOutputStream(socket.getOutputStream());
    pushedBack = -1;
  }

  /**
   * Turns a capability response into capabilities.
   *
   * @param response the response
   * @return the capabilities
   * @throws ManageSieveException when a line holds something else than strings
   */
  private static ManageSieveCapabilities capabilitiesOf(Response response) throws ManageSieveException {
    List<List<String>> lines = new ArrayList<>();
    for (List<Token> line : response.lines) {
      List<String> values = new ArrayList<>();
      for (Token token : line) {
        if (token.type != TokenType.STRING) {
          throw new ManageSieveException(Kind.PROTOCOL, "Malformed capability line");
        }
        values.add(token.value);
      }
      lines.add(values);
    }
    return ManageSieveCapabilities.fromLines(lines);
  }

  /**
   * Sends a command and reads its response.
   *
   * @param name the command name, the only part of it that may appear in an error
   * @param line the command line, possibly ending with a literal
   * @return the response
   * @throws ManageSieveException {@link Kind#UNAVAILABLE} on an I/O error or a timeout
   */
  private Response command(String name, String line) throws ManageSieveException {
    startOperation();
    try {
      write(line);
      return readResponse();
    } catch (IOException e) {
      throw unavailable(name, e);
    }
  }

  /**
   * Fails unless the response is {@code OK}.
   *
   * @param response the response
   * @param name the command name
   * @throws ManageSieveException {@link Kind#REFUSED} on {@code NO},
   *           {@link Kind#PROTOCOL} on {@code BYE}
   */
  private void requireOk(Response response, String name) throws ManageSieveException {
    if (response.status == Status.OK) {
      return;
    }
    String detail = response.message == null ? "" : ": " + printable(response.message);
    if (response.status == Status.BYE) {
      close();
      throw new ManageSieveException(Kind.PROTOCOL, response.code, "The ManageSieve server closed the connection on " + name
          + detail, null);
    }
    throw new ManageSieveException(Kind.REFUSED, response.code, "The ManageSieve server refused " + name + detail, null);
  }

  /**
   * Starts the clock of one operation.
   */
  private void startOperation() {
    deadlineNanos = System.nanoTime() + operationTimeoutMillis * 1_000_000L;
  }

  /**
   * The server's text made safe for a log line: control characters replaced by spaces,
   * truncated.
   *
   * @param text the server's text
   * @return one line of at most {@value #MAX_MESSAGE_CHARS} characters
   */
  static String printable(String text) {
    String line = text.replaceAll("\\p{Cntrl}", " ");
    return line.length() > MAX_MESSAGE_CHARS ? line.substring(0, MAX_MESSAGE_CHARS) + "…" : line;
  }

  /**
   * Wraps an I/O failure.
   *
   * @param name what was being done
   * @param e the failure
   * @return the exception to throw
   */
  private ManageSieveException unavailable(String name, IOException e) {
    close();
    String reason = e instanceof SocketTimeoutException ? " timed out" : " failed";
    return new ManageSieveException(Kind.UNAVAILABLE, "ManageSieve " + name + reason + " on " + host + ":" + port, e);
  }

  /**
   * Writes one command line, which may embed literals, followed by CRLF.
   *
   * @param line the line
   * @throws IOException when the socket fails
   */
  private void write(String line) throws IOException {
    out.write(line.getBytes(StandardCharsets.UTF_8));
    out.write(CRLF);
    out.flush();
  }

  /**
   * Encodes a string argument: quoted when short and single-line, a non-synchronizing
   * literal otherwise (RFC 5804 §4). A NUL is refused: no Sieve name or text has one.
   *
   * @param value the value
   * @return the encoded argument
   */
  static String string(String value) {
    if (value == null) {
      throw new IllegalArgumentException("A ManageSieve string argument is required");
    }
    if (value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("A ManageSieve string cannot contain NUL");
    }
    if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
        || value.getBytes(StandardCharsets.UTF_8).length > MAX_QUOTED_OCTETS) {
      return literal(value);
    }
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  /**
   * Encodes a value as a non-synchronizing literal, {@code {n+}CRLF} followed by its
   * UTF-8 octets; the count is of octets, not of characters.
   *
   * @param value the value
   * @return the encoded literal
   */
  static String literal(String value) {
    if (value == null) {
      throw new IllegalArgumentException("A ManageSieve literal is required");
    }
    if (value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("A ManageSieve literal cannot contain NUL");
    }
    return "{" + value.getBytes(StandardCharsets.UTF_8).length + "+}\r\n" + value;
  }

  /**
   * Reads one response: data lines until the {@code OK}, {@code NO} or {@code BYE} line.
   *
   * @return the response
   * @throws IOException when the socket fails
   * @throws ManageSieveException when the response is malformed or too large
   */
  private Response readResponse() throws IOException, ManageSieveException {
    responseOctets = 0;
    List<List<Token>> lines = new ArrayList<>();
    while (true) {
      List<Token> line = readLine();
      if (!line.isEmpty() && line.get(0).type == TokenType.ATOM) {
        Status status = statusOf(line.get(0).value);
        if (status != null) {
          return finalLine(status, line, lines);
        }
      }
      lines.add(line);
      if (lines.size() > MAX_RESPONSE_LINES) {
        throw new ManageSieveException(Kind.PROTOCOL, "The ManageSieve response is too long");
      }
    }
  }

  /**
   * Reads the optional response code and message of a final line.
   *
   * @param status the status
   * @param line the final line's tokens
   * @param lines the data lines before it
   * @return the response
   * @throws ManageSieveException when the line is malformed
   */
  private static Response finalLine(Status status, List<Token> line, List<List<Token>> lines) throws ManageSieveException {
    int index = 1;
    String code = null;
    if (index < line.size() && line.get(index).type == TokenType.LPAREN) {
      index++;
      if (index >= line.size() || line.get(index).type != TokenType.ATOM) {
        throw new ManageSieveException(Kind.PROTOCOL, "Malformed ManageSieve response code");
      }
      code = line.get(index).value.toUpperCase(Locale.ROOT);
      while (index < line.size() && line.get(index).type != TokenType.RPAREN) {
        index++;
      }
      if (index >= line.size()) {
        throw new ManageSieveException(Kind.PROTOCOL, "Unterminated ManageSieve response code");
      }
      index++;
    }
    String message = index < line.size() && line.get(index).type == TokenType.STRING ? line.get(index).value : null;
    return new Response(status, code, message, lines);
  }

  /**
   * The status an atom names.
   *
   * @param atom the atom
   * @return the status, or null when the atom is not one
   */
  private static Status statusOf(String atom) {
    return switch (atom.toUpperCase(Locale.ROOT)) {
    case "OK" -> Status.OK;
    case "NO" -> Status.NO;
    case "BYE" -> Status.BYE;
    default -> null;
    };
  }

  /**
   * Reads the tokens of one line, a literal's octets included.
   *
   * @return the tokens
   * @throws IOException when the socket fails
   * @throws ManageSieveException when a token is malformed or too large
   */
  private List<Token> readLine() throws IOException, ManageSieveException {
    List<Token> tokens = new ArrayList<>();
    while (true) {
      int c = read();
      if (c == ' ') {
        continue;
      }
      if (c == '\r') {
        if (read() != '\n') {
          throw new ManageSieveException(Kind.PROTOCOL, "Bare CR in a ManageSieve response");
        }
        return tokens;
      }
      if (c == '\n') {
        return tokens;
      }
      if (c == '"') {
        tokens.add(new Token(TokenType.STRING, readQuoted()));
      } else if (c == '{') {
        tokens.add(new Token(TokenType.STRING, readLiteral()));
      } else if (c == '(') {
        tokens.add(new Token(TokenType.LPAREN, "("));
      } else if (c == ')') {
        tokens.add(new Token(TokenType.RPAREN, ")"));
      } else {
        tokens.add(new Token(TokenType.ATOM, readAtom(c)));
      }
      if (tokens.size() > MAX_TOKENS_PER_LINE) {
        throw new ManageSieveException(Kind.PROTOCOL, "The ManageSieve response line is too long");
      }
    }
  }

  /**
   * Reads a quoted string after its opening quote.
   *
   * @return the unescaped value
   * @throws IOException when the socket fails
   * @throws ManageSieveException when the string is too long
   */
  private String readQuoted() throws IOException, ManageSieveException {
    ByteArrayOutputStream value = new ByteArrayOutputStream();
    while (true) {
      int c = read();
      if (c == '"') {
        return value.toString(StandardCharsets.UTF_8);
      }
      if (c == '\\') {
        c = read();
      }
      if (c == '\r' || c == '\n') {
        throw new ManageSieveException(Kind.PROTOCOL, "Line break in a ManageSieve quoted string");
      }
      value.write(c);
      if (value.size() > MAX_SERVER_QUOTED_OCTETS) {
        throw new ManageSieveException(Kind.PROTOCOL, "ManageSieve quoted string too long");
      }
    }
  }

  /**
   * Reads a literal after its opening brace: the octet count, the closing brace, CRLF,
   * then exactly that many octets.
   *
   * @return the literal's value, decoded as UTF-8
   * @throws IOException when the socket fails
   * @throws ManageSieveException when the count is malformed or above the cap
   */
  private String readLiteral() throws IOException, ManageSieveException {
    long count = 0;
    int digits = 0;
    int c = read();
    while (c >= '0' && c <= '9') {
      count = count * 10 + (c - '0');
      digits++;
      if (count > MAX_LITERAL_OCTETS) {
        throw new ManageSieveException(Kind.PROTOCOL, "ManageSieve literal larger than " + MAX_LITERAL_OCTETS + " octets");
      }
      c = read();
    }
    if (c == '+') {
      c = read();
    }
    if (digits == 0 || c != '}' || read() != '\r' || read() != '\n') {
      throw new ManageSieveException(Kind.PROTOCOL, "Malformed ManageSieve literal");
    }
    if (responseOctets + count > MAX_RESPONSE_OCTETS) {
      throw new ManageSieveException(Kind.PROTOCOL, "ManageSieve response larger than " + MAX_RESPONSE_OCTETS + " octets");
    }
    byte[] octets = new byte[(int) count];
    int offset = 0;
    if (count > 0 && pushedBack >= 0) {
      octets[offset++] = (byte) pushedBack;
      pushedBack = -1;
    }
    while (offset < count) {
      checkDeadline();
      int read = in.read(octets, offset, (int) count - offset);
      if (read < 0) {
        throw new EOFException("ManageSieve connection closed inside a literal");
      }
      offset += read;
    }
    responseOctets += count;
    return new String(octets, StandardCharsets.UTF_8);
  }

  /**
   * Reads an atom starting with an already-read octet, up to a delimiter it leaves
   * unread.
   *
   * @param first the first octet
   * @return the atom
   * @throws IOException when the socket fails
   * @throws ManageSieveException when the atom is too long
   */
  private String readAtom(int first) throws IOException, ManageSieveException {
    StringBuilder atom = new StringBuilder();
    int c = first;
    while (c != ' ' && c != '\r' && c != '\n' && c != '(' && c != ')' && c != '"' && c != '{') {
      atom.append((char) c);
      if (atom.length() > MAX_ATOM_OCTETS) {
        throw new ManageSieveException(Kind.PROTOCOL, "ManageSieve atom too long");
      }
      c = read();
    }
    pushedBack = c;
    return atom.toString();
  }

  /**
   * Reads one octet, the pushed-back one first.
   *
   * @return the octet
   * @throws IOException when the socket fails, the server closed the connection or the
   *           operation's deadline passed
   * @throws ManageSieveException when the response exceeds its octet budget
   */
  private int read() throws IOException, ManageSieveException {
    if (pushedBack >= 0) {
      int c = pushedBack;
      pushedBack = -1;
      return c;
    }
    checkDeadline();
    int c = in.read();
    if (c < 0) {
      throw new EOFException("ManageSieve connection closed by the server");
    }
    if (++responseOctets > MAX_RESPONSE_OCTETS) {
      throw new ManageSieveException(Kind.PROTOCOL, "ManageSieve response larger than " + MAX_RESPONSE_OCTETS + " octets");
    }
    return c;
  }

  /**
   * Fails the operation once its deadline has passed.
   *
   * @throws SocketTimeoutException when it has
   */
  private void checkDeadline() throws SocketTimeoutException {
    if (System.nanoTime() - deadlineNanos > 0) {
      throw new SocketTimeoutException("ManageSieve operation deadline exceeded");
    }
  }

  /** The final status of a response. */
  private enum Status {
    OK, NO, BYE
  }

  /** What a response token is. */
  private enum TokenType {
    ATOM, STRING, LPAREN, RPAREN
  }

  /**
   * One token of a response line.
   *
   * @param type what the token is
   * @param value its text, a quoted string or literal unescaped
   */
  private record Token(TokenType type, String value) {
  }

  /**
   * One response.
   *
   * @param status the final status
   * @param code the response code, upper-case, or null
   * @param message the human-readable text, or null
   * @param lines the data lines before the final one
   */
  private record Response(Status status, String code, String message, List<List<Token>> lines) {
  }
}
