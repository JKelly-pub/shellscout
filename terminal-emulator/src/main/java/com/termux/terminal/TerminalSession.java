package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session coupling a {@link TerminalEmulator} to a byte transport.
 * <p>
 * MODIFIED for ShellScout: the upstream Termux class forks a local process through JNI. This version
 * has no native code. Bytes are supplied by a {@link Transport} (an SSH shell channel in practice):
 * the transport pushes remote output in with {@link #feedFromRemote(byte[], int, int)} and receives
 * keyboard input through {@link Transport#write(byte[], int, int)} on a background writer thread.
 * <p>
 * All terminal emulation and client callbacks run on the main thread.
 */
public final class TerminalSession extends TerminalOutput {

    /** The remote end of the session, e.g. an SSH shell channel. */
    public interface Transport {
        /** Called once, on the main thread, when the emulator size first becomes known. */
        void onStart(TerminalSession session, int columns, int rows, int cellWidthPixels, int cellHeightPixels);

        /** Write keyboard input to the remote end. Called on a background thread; may block. */
        void write(byte[] data, int offset, int count) throws java.io.IOException;

        /** The terminal size changed. Called on the main thread; must not block. */
        void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels);
    }

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_REMOTE_CLOSED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    private final byte[] mUtf8InputBuffer = new byte[5];

    TerminalSessionClient mClient;
    private final Transport mTransport;
    private final Integer mTranscriptRows;

    private volatile boolean mRunning = true;
    private volatile boolean mStarted = false;
    private int mExitStatus;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(Transport transport, Integer transcriptRows, TerminalSessionClient client) {
        this.mTransport = transport;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null) mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the remote pty of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
            if (mRunning) mTransport.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
        if (mStarted) return;
        mStarted = true;

        new Thread("TermSessionOutputWriter[" + mHandle + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try {
                    while (true) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        mTransport.write(buffer, 0, bytesToWrite);
                    }
                } catch (Exception e) {
                    // Remote end gone; onRemoteClosed() will be called by the transport.
                }
            }
        }.start();

        mTransport.onStart(this, columns, rows, cellWidthPixels, cellHeightPixels);
    }

    /**
     * Push output received from the remote end. Safe to call from any thread. Returns false if the
     * session has been closed.
     */
    public boolean feedFromRemote(byte[] data, int offset, int count) {
        if (!mProcessToTerminalIOQueue.write(data, offset, count)) return false;
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
        return true;
    }

    /** Called by the transport when the remote end closes. Safe to call from any thread. */
    public void onRemoteClosed(int exitStatus, String reason) {
        mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_REMOTE_CLOSED, exitStatus, 0, reason));
    }

    /** Write a local status line into the terminal (e.g. "[Connection lost]"). Main thread only. */
    public void appendLocalMessage(String text) {
        if (mEmulator == null) return;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        mEmulator.append(bytes, bytes.length);
        notifyScreenUpdate();
    }

    @Override
    public void write(byte[] data, int offset, int count) {
        if (mRunning) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= 0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= 0b11111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= 0b1111111111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Stop the writer thread. The owner is responsible for closing the transport itself. */
    public void finishIfRunning() {
        cleanupResources(0);
    }

    void cleanupResources(int exitStatus) {
        synchronized (this) {
            mRunning = false;
            mExitStatus = exitStatus;
        }
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mRunning;
    }

    public synchronized int getExitStatus() {
        return mExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[16 * 1024];

        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            if (mEmulator != null) {
                int bytesRead;
                boolean any = false;
                // Drain everything queued so heavy output is coalesced into one redraw (RP-01).
                while ((bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false)) > 0) {
                    mEmulator.append(mReceiveBuffer, bytesRead);
                    any = true;
                }
                if (any) notifyScreenUpdate();
            }

            if (msg.what == MSG_REMOTE_CLOSED && isRunning()) {
                int exitCode = msg.arg1;
                String reason = (String) msg.obj;
                cleanupResources(exitCode);
                String text = "\r\n[" + (reason != null ? reason : "Session closed")
                    + (exitCode > 0 ? " (exit " + exitCode + ")" : "") + "]\r\n";
                appendLocalMessage(text);
                mClient.onSessionFinished(TerminalSession.this);
            }
        }
    }
}
