package de.jungblut.datastructure;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * BufferedOutputStream that asynchronously flushes to disk, so callers don't
 * have to wait until the flush happens. Buffers are put into a queue that is
 * written asynchronously to disk once it is really available. TODO To make it
 * use less memory if the producer is too fast, this should limit the number of
 * buffers. Also it should not create new byte arrays everytime then.
 * 
 * @author thomas.jungblut
 * 
 */
public final class AsyncBufferedOutputStream extends FilterOutputStream {

  private final FlushThread flusher = new FlushThread();
  private final Thread flusherThread = new Thread(flusher, "FlushThread");
  private final BlockingQueue<byte[]> buffers = new LinkedBlockingQueue<>();

  private final byte[] buf;

  private int count = 0;

  public AsyncBufferedOutputStream(OutputStream out) {
    this(out, 8 * 1024);
  }

  public AsyncBufferedOutputStream(OutputStream out, int bufSize) {
    super(out);
    buf = new byte[bufSize];
    flusherThread.start();
  }

  /**
   * Flush the internal buffer by copying a new byte array into the buffer
   * blocking queue
   */
  private void flushBuffer() throws IOException {
    if (count > 0) {
      final byte[] copy = new byte[count];
      System.arraycopy(buf, 0, copy, 0, copy.length);
      buffers.add(copy);
      count = 0;
    }
  }

  /**
   * Writes the specified byte to this buffered output stream.
   * 
   * @param b the byte to be written.
   * @exception IOException if an I/O error occurs.
   */
  @Override
  public void write(int b) throws IOException {
    if (count >= buf.length) {
      flushBuffer();
    }
    buf[count++] = (byte) b;
  }

  /**
   * Writes <code>len</code> bytes from the specified byte array starting at
   * offset <code>off</code> to this buffered output stream.
   * 
   * @param b the data.
   * @param off the start offset in the data.
   * @param len the number of bytes to write.
   * @exception IOException if an I/O error occurs.
   */
  @Override
  public void write(byte b[], int off, int len) throws IOException {
    for (int i = off; i < len; i++) {
      write(b[i]);
    }
  }

  /**
   * Flushes this buffered output stream. It will enforce that the current
   * buffer will be queue for asynchronous flushing.
   * 
   * @exception IOException if an I/O error occurs.
   * @see java.io.FilterOutputStream#out
   */
  @Override
  public void flush() throws IOException {
    flushBuffer();
  }

  @Override
  public void close() throws IOException {
    flush();
    flusher.closed = true;
    try {
      flusherThread.interrupt();
      flusherThread.join();
    } catch (InterruptedException e) {
      // this is expected to happen
    }
    out.close();
  }

  class FlushThread implements Runnable {

    volatile boolean closed = false;

    @Override
    public void run() {
      // run the real flushing action to the underlying stream
      try {
        while (!closed) {
          byte[] take = buffers.take();
          out.write(take);
        }
      } catch (IOException e) {
        e.printStackTrace();
      } catch (InterruptedException e) {
        // this is expected to happen
      } finally {
        try {
          // write the last buffers to the streams
          for (byte[] buf : buffers) {
            out.write(buf);
          }
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

}
