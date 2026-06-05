package dev.morling.onebrc;

import java.nio.ByteBuffer;

public class Searcher {

  static long searchForFirstSemicolon(ByteBuffer b) {
    return searchByteBuffer(b, 0x3B3B3B3B3B3B3B3BL);
  }

  static long searchForFirstNewline(ByteBuffer b) {
    return searchByteBuffer(b, 0x0A0A0A0A0A0A0A0AL);
  }

  private static long searchByteBuffer(ByteBuffer b, long wordCharacter) {
    var position = b.position();
    var limit = b.limit();
    var remaining = b.remaining();
    var word = b.clear().getLong();
    word = word << position;

    b.position(position).limit(limit);

    var res = searchWord(word, wordCharacter);
    if (res >= 0 && res < remaining) {
      return res;
    }
    return -1;
  }

  private static long searchWord(long word, long wordCharacter) {
    // 0x3B is the ';' byte representation, for example
    // XORing search word with it with will zero the byte where ';' is located
    long x = word ^ wordCharacter;

    return searchWordForFirstZeroByte(x);
  }

  private static long searchWordForFirstZeroByte(long word) {
    // First, subtract 1 from each byte. 0x00 will flip to 0xFF with high bit set - that's what we need
    // For any b from 1..0x7F, b-1 is 0..0x7E - high bit clear
    // For any b from 0x81 to 0xFF, b-1 will still have high bit set

    // Second, ~x flips every bit.
    // For any b from 1..0x7F, b-1 is 0..0x7E - high bit now is set, but we do ... & ~x, so resulting high bits will
    // be clear
    // For any b from 0x81 to 0xFF ~x will flip the high bit to clear

    // Now, the high bit of only 0x00 bytes in the original word is set

    // Third, zero lower 7 bites of every byte: ... & 0x8080808080808080L
    long m = (word - 0x0101010101010101L) & ~word & 0x8080808080808080L;

    // Now, the only bits set in the whole word m are the high bits of the bytes that were zero in the original word
    // All we have to do: find how many trailing zeros are there in a word
    // and divide by 8 to obtain a byte index of the first zero byte in the original word
    var byteIndex = Long.numberOfTrailingZeros(m) / 8;

    // If an original word does not have a zero byte at all,
    // then numberOfTrailingZeros returns 64, which gives 64 / 8 = 8
    return byteIndex == 8 ? -1 : byteIndex;
  }
}
