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
