/*
 * Copyright (c) 2001, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.nio.ch;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.channels.spi.*;
import java.util.Objects;

/**
 * This class is defined here rather than in java.nio.channels.Channels
 * so that code can be shared with SocketAdaptor.
 *
 * @author Mike McCloskey
 * @author Mark Reinhold
 * @since 1.4
 */

public class ChannelInputStream
    extends InputStream
{

    public static int read(ReadableByteChannel ch, ByteBuffer bb,
                           boolean block)
        throws IOException
    {
        if (ch instanceof SelectableChannel) {
            SelectableChannel sc = (SelectableChannel)ch;
            synchronized (sc.blockingLock()) {
                boolean bm = sc.isBlocking();
                if (!bm)
                    throw new IllegalBlockingModeException();
                if (bm != block)
                    sc.configureBlocking(block);
                int n = ch.read(bb);
                if (bm != block)
                    sc.configureBlocking(bm);
                return n;
            }
        } else {
            return ch.read(bb);
        }
    }

    protected final ReadableByteChannel ch;
    private ByteBuffer bb = null;
    private byte[] bs = null;           // Invoker's previous array
    private byte[] b1 = null;

    public ChannelInputStream(ReadableByteChannel ch) {
        this.ch = ch;
    }

    public synchronized int read() throws IOException {
        if (b1 == null)
            b1 = new byte[1];
        int n = this.read(b1);
        if (n == 1)
            return b1[0] & 0xff;
        return -1;
    }

    public synchronized int read(byte[] bs, int off, int len)
        throws IOException
    {
        Objects.checkFromIndexSize(off, len, bs.length);
        if (len == 0)
            return 0;

        ByteBuffer bb = ((this.bs == bs)
                         ? this.bb
                         : ByteBuffer.wrap(bs));
        bb.limit(Math.min(off + len, bb.capacity()));
        bb.position(off);
        this.bb = bb;
        this.bs = bs;
        return read(bb);
    }

    protected int read(ByteBuffer bb)
        throws IOException
    {
        return ChannelInputStream.read(ch, bb, true);
    }

    public int available() throws IOException {
        // special case where the channel is to a file
        if (ch instanceof SeekableByteChannel) {
            SeekableByteChannel sbc = (SeekableByteChannel)ch;
            long rem = Math.max(0, sbc.size() - sbc.position());
            return (rem > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int)rem;
        }
        return 0;
    }

    public synchronized long skip(long n) throws IOException {
        // special case where the channel is to a file
        if (ch instanceof SeekableByteChannel) {
            SeekableByteChannel sbc = (SeekableByteChannel)ch;
            long pos = sbc.position();
            long newPos;
            if (n > 0) {
                newPos = pos + n;
                long size = sbc.size();
                if (newPos < 0 || newPos > size) {
                    newPos = size;
                }
            } else {
                newPos = Long.max(pos + n, 0);
            }
            sbc.position(newPos);
            return newPos - pos;
        }
        return super.skip(n);
    }

    public void close() throws IOException {
        ch.close();
    }

    private static final int TRANSFER_SIZE = 8192;

    @Override
    public long transferTo(OutputStream out) throws IOException {
        Objects.requireNonNull(out, "out");

        if (out instanceof ChannelOutputStream cos) {
            WritableByteChannel wbc = cos.channel();

            if (ch instanceof FileChannel fc) {
                return transfer(fc, wbc);
            }

            if (wbc instanceof FileChannel fc) {
                if (ch instanceof SeekableByteChannel sbc) {
                    return transfer(sbc, fc);
                }

                return transfer(ch, fc);
            }

            return transfer(ch, wbc);
        }

        return super.transferTo(out);
    }

    private static long transfer(FileChannel src, WritableByteChannel dst) throws IOException {
        long bytesWritten = 0L;
        long srcPos = src.position();
        try {
            while (srcPos + bytesWritten < src.size()) {
                bytesWritten += src.transferTo(srcPos + bytesWritten, Long.MAX_VALUE, dst);
            }
            return bytesWritten;
        } finally {
            src.position(srcPos + bytesWritten);
        }
    }

    private static long transfer(SeekableByteChannel src, FileChannel dst) throws IOException {
        long bytesWritten = 0L;
        long dstPos = dst.position();
        long srcPos = src.position();
        try {
            while (srcPos + bytesWritten < src.size()) {
                bytesWritten += dst.transferFrom(src, dstPos + bytesWritten, Long.MAX_VALUE);
            }
            return bytesWritten;
        } finally {
            dst.position(dstPos + bytesWritten);
        }
    }

    private static long transfer(ReadableByteChannel src, FileChannel dst) throws IOException {
        long bytesWritten = 0L;
        long dstPos = dst.position();
        ByteBuffer bb = Util.getTemporaryDirectBuffer(TRANSFER_SIZE);
        try {
            int bytesRead;
            do {
                bytesWritten += dst.transferFrom(src, dstPos + bytesWritten, Long.MAX_VALUE);
                bytesRead = src.read(bb); // detect end-of-stream
                if (bytesRead > -1) {
                    bb.flip();
                    while (bb.hasRemaining())
                        dst.write(bb);
                    bb.clear();
                    bytesWritten += bytesRead;
                }
            } while (bytesRead > -1);
            return bytesWritten;
        } finally {
            dst.position(dstPos + bytesWritten);
            Util.releaseTemporaryDirectBuffer(bb);
        }
    }

    private static long transfer(ReadableByteChannel src, WritableByteChannel dst) throws IOException {
        long bytesWritten = 0L;
        ByteBuffer bb = Util.getTemporaryDirectBuffer(TRANSFER_SIZE);
        try {
            for (int bytesRead = src.read(bb); bytesRead > -1; bytesRead = src.read(bb)) {
                bb.flip();
                while (bb.hasRemaining())
                    dst.write(bb);
                bb.clear();
                bytesWritten += bytesRead;
            }
            return bytesWritten;
        } finally {
            Util.releaseTemporaryDirectBuffer(bb);
        }
    }
}
