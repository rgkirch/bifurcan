package io.lacuna.bifurcan.durable.blocks;

import io.lacuna.bifurcan.*;
import io.lacuna.bifurcan.durable.BlockPrefix;
import io.lacuna.bifurcan.durable.BlockPrefix.BlockType;
import io.lacuna.bifurcan.durable.DurableAccumulator;
import io.lacuna.bifurcan.durable.Util;
import io.lacuna.bifurcan.durable.Util.Block;
import io.lacuna.bifurcan.utils.Iterators;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An indexed list, encoded as:
 * - the number of elements [VLQ]
 * - number of SkipTable tiers [uint8]
 * - a SkipTable over the blocks of elements (unless number of tiers is 0)
 * - zero or more ENCODED blocks generated by {@code DurableEncoding.elementEncoding(index)}
 */
public class List {

  private static class Indexed<V> {
    public final long index;
    public final V value;

    public Indexed(V value, long index) {
      this.value = value;
      this.index = index;
    }
  }

  public static <V> void encode(Iterator<V> it, DurableEncoding e, DurableOutput out) {
    SkipTable.Writer skipTable = new SkipTable.Writer();
    DurableAccumulator elements = new DurableAccumulator();

    AtomicLong counter = new AtomicLong();
    Iterator<Block<Indexed<V>, DurableEncoding>> blocks =
        Util.partitionBy(
            Iterators.map(it, v -> new Indexed<>(v, counter.getAndIncrement())),
            i -> e.elementEncoding(i.index),
            DurableEncoding::blockSize,
            DurableEncoding::equals,
            i -> Util.isCollection(i.value));

    long index = 0;
    while (blocks.hasNext()) {
      Block<Indexed<V>, DurableEncoding> b = blocks.next();
      skipTable.append(index, elements.written());
      Util.encodeBlock(Lists.lazyMap(b.elements, i -> i.value), b.encoding, elements);
      index += b.elements.size();
    }

    long size = index;
    DurableAccumulator.flushTo(out, BlockType.LIST, acc -> {
      acc.writeVLQ(size);
      acc.writeUnsignedByte(skipTable.tiers());
      if (skipTable.tiers() > 0) {
        skipTable.flushTo(acc);
      }
      elements.flushTo(acc);
    });
  }

  public static DurableList decode(DurableInput in, IDurableCollection.Root root, DurableEncoding encoding) {
    DurableInput bytes = in.duplicate();

    BlockPrefix prefix = in.readPrefix();
    assert (prefix.type == BlockType.LIST);
    long pos = in.position();

    long size = in.readVLQ();
    int skipTableTiers = in.readUnsignedByte();

    SkipTable skipTable = null;
    if (skipTableTiers > 0) {
      skipTable = new SkipTable(in.sliceBlock(BlockType.TABLE), skipTableTiers);
    }

    DurableInput elements = in.sliceBytes((pos + prefix.length) - in.position());

    return new DurableList(bytes, root, size, skipTable, elements, encoding);
  }


}
