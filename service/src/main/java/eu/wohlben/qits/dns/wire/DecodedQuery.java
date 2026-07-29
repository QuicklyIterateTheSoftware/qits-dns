package eu.wohlben.qits.dns.wire;

import java.util.OptionalInt;

/**
 * A parsed DNS query, reduced to what the listeners and the resolver need — deliberately not a
 * codec library's message type, so that nothing above this seam names one.
 *
 * <p>{@code qname} is null and {@code qtype} is 0 when {@code qdcount != 1}. That is a parseable
 * message which is not a single-question query, and the wire layer answers FORMERR to it; the
 * fields are absent rather than defaulted because there is genuinely no question to report. {@code
 * qname} is lowercased and carries no trailing dot, which is the spelling the resolution contract
 * is written against.
 *
 * <p>{@code ednsPayloadSize} is empty when the query carried no OPT record. Present, it is the
 * client's advertised UDP budget, and the effective response budget is the smaller of it and 1232 —
 * the size that survives the internet's real-world MTU without fragmenting.
 *
 * <p>{@code wire} is the ORIGINAL bytes. It is here so an implementation can rebuild the response
 * from the original question and echo the querier's exact capitalisation, which DNS requires,
 * without this seam having to expose a parsed-question type from whichever codec is in use. It also
 * means the seam survives replacing that codec: a hand-rolled implementation gets the same input.
 * The array is not defensively copied — it is the receive buffer's contents and nothing downstream
 * writes to it.
 *
 * <p>It holds EXACTLY the message, {@code length} bytes and no more: {@link DnsCodec#decode} is
 * handed a buffer and a length, and it is the decoder that trims when those differ. So {@code
 * wire.length} is the message length and a re-parse of this array parses the same message, which is
 * what makes rebuilding the response from it sound rather than merely usual. (Both listeners hand
 * over exact arrays already — a datagram's payload and a length-prefixed TCP frame are both framed
 * for us — so the trim is a contract, not a copy anyone pays for.)
 */
public record DecodedQuery(
    int id,
    String qname,
    int qtype,
    int qclass,
    int opcode,
    int qdcount,
    OptionalInt ednsPayloadSize,
    byte[] wire) {}
