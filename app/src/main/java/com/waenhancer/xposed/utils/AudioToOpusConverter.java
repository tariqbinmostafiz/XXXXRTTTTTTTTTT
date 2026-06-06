package com.waenhancer.xposed.utils;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import de.robv.android.xposed.XposedBridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Random;

public class AudioToOpusConverter {

    private static final int OPUS_SAMPLE_RATE = 48000;
    private static final int OPUS_CHANNEL_COUNT = 1; // Mono is standard and keeps size down
    private static final int OPUS_BIT_RATE = 24000;
    private static final int TIMEOUT_US = 5000;

    public static File convert(Context context, Uri inputUri) {
        File cacheDir = context.getCacheDir();
        File outFile = new File(cacheDir, "transcoded_audio_" + System.currentTimeMillis() + ".opus");

        ParcelFileDescriptor pfd = null;
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        FileOutputStream fos = null;

        try {
            pfd = context.getContentResolver().openFileDescriptor(inputUri, "r");
            if (pfd == null) {
                throw new java.io.IOException("Failed to open input Uri: " + inputUri);
            }

            extractor = new MediaExtractor();
            extractor.setDataSource(pfd.getFileDescriptor());

            int audioTrackIndex = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    break;
                }
            }

            if (audioTrackIndex == -1) {
                throw new java.io.IOException("No audio track found in input file");
            }

            extractor.selectTrack(audioTrackIndex);
            MediaFormat inputFormat = extractor.getTrackFormat(audioTrackIndex);

            if (!hasOpusEncoder()) {
                throw new java.io.IOException("Device does not support audio/opus encoding");
            }

            String inputMime = inputFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(inputMime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            MediaFormat outputFormat = MediaFormat.createAudioFormat(MediaFormat.KEY_MIME, OPUS_SAMPLE_RATE, OPUS_CHANNEL_COUNT);
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, OPUS_BIT_RATE);

            encoder = MediaCodec.createEncoderByType("audio/opus");
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            fos = new FileOutputStream(outFile);
            OggOpusWriter writer = new OggOpusWriter(fos, OPUS_CHANNEL_COUNT);
            writer.writeHeaders();

            transcode(extractor, decoder, encoder, writer, inputFormat);
            writer.close();

            return outFile;

        } catch (Throwable t) {
            XposedBridge.log("[WAEX-AudioToOpus] Error transcoding audio: " + t.toString());
            if (outFile.exists()) {
                outFile.delete();
            }
            return null;
        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) {}
                try { decoder.release(); } catch (Exception ignored) {}
            }
            if (encoder != null) {
                try { encoder.stop(); } catch (Exception ignored) {}
                try { encoder.release(); } catch (Exception ignored) {}
            }
            if (extractor != null) {
                try { extractor.release(); } catch (Exception ignored) {}
            }
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) {}
            }
            if (fos != null) {
                try { fos.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static boolean hasOpusEncoder() {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo info : codecList.getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if ("audio/opus".equalsIgnoreCase(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void transcode(MediaExtractor extractor, MediaCodec decoder, MediaCodec encoder, OggOpusWriter writer, MediaFormat inputFormat) throws IOException {
        int inputSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int inputChannelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

        ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
        ByteBuffer[] decoderOutputBuffers = decoder.getOutputBuffers();
        ByteBuffer[] encoderInputBuffers = encoder.getInputBuffers();
        ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();

        MediaCodec.BufferInfo decoderBufferInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo encoderBufferInfo = new MediaCodec.BufferInfo();

        boolean extractorDone = false;
        boolean decoderDone = false;
        boolean encoderInputDone = false;
        boolean encoderDone = false;

        Resampler resampler = new Resampler(inputSampleRate, inputChannelCount, OPUS_SAMPLE_RATE, OPUS_CHANNEL_COUNT);

        // FIFO buffer for PCM data
        java.io.ByteArrayOutputStream pcmQueue = new java.io.ByteArrayOutputStream();
        byte[] pcmQueueBytes = new byte[0];
        int pcmQueueOffset = 0;
        long lastPtsUs = 0;

        while (!encoderDone) {
            // 1. Feed Extractor to Decoder
            if (!extractorDone) {
                int decoderInputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (decoderInputBufferIndex >= 0) {
                    ByteBuffer dstBuf = decoderInputBuffers[decoderInputBufferIndex];
                    dstBuf.clear();
                    int sampleSize = extractor.readSampleData(dstBuf, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(decoderInputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        extractorDone = true;
                    } else {
                        long presentationTimeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(decoderInputBufferIndex, 0, sampleSize, presentationTimeUs, 0);
                        extractor.advance();
                    }
                }
            }

            // 2. Pull Decoder Output to PCM Queue
            if (!decoderDone) {
                int decoderOutputBufferIndex = decoder.dequeueOutputBuffer(decoderBufferInfo, TIMEOUT_US);
                if (decoderOutputBufferIndex >= 0) {
                    ByteBuffer pcmBuf = decoderOutputBuffers[decoderOutputBufferIndex];
                    boolean isEOS = (decoderBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;

                    if (decoderBufferInfo.size > 0) {
                        byte[] resampledData = resampler.resample(pcmBuf, decoderBufferInfo.offset, decoderBufferInfo.size);
                        pcmQueue.write(resampledData);
                        lastPtsUs = decoderBufferInfo.presentationTimeUs;
                    }

                    decoder.releaseOutputBuffer(decoderOutputBufferIndex, false);

                    if (isEOS) {
                        decoderDone = true;
                    }
                } else if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    decoderOutputBuffers = decoder.getOutputBuffers();
                } else if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = decoder.getOutputFormat();
                    int newRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    int newChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    resampler = new Resampler(newRate, newChannels, OPUS_SAMPLE_RATE, OPUS_CHANNEL_COUNT);
                }
            }

            // Convert pcmQueue to active array if new bytes are written
            if (pcmQueue.size() > 0) {
                byte[] newBytes = pcmQueue.toByteArray();
                pcmQueue.reset();
                if (pcmQueueBytes.length - pcmQueueOffset == 0) {
                    pcmQueueBytes = newBytes;
                    pcmQueueOffset = 0;
                } else {
                    byte[] combined = new byte[(pcmQueueBytes.length - pcmQueueOffset) + newBytes.length];
                    System.arraycopy(pcmQueueBytes, pcmQueueOffset, combined, 0, pcmQueueBytes.length - pcmQueueOffset);
                    System.arraycopy(newBytes, 0, combined, pcmQueueBytes.length - pcmQueueOffset, newBytes.length);
                    pcmQueueBytes = combined;
                    pcmQueueOffset = 0;
                }
            }

            // 3. Feed PCM Queue to Encoder
            int availableBytes = pcmQueueBytes.length - pcmQueueOffset;
            if (!encoderInputDone && (availableBytes > 0 || decoderDone)) {
                int encoderInputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_US);
                if (encoderInputBufferIndex >= 0) {
                    ByteBuffer dstBuf = encoderInputBuffers[encoderInputBufferIndex];
                    dstBuf.clear();

                    int bytesToCopy = Math.min(availableBytes, dstBuf.remaining());
                    if (bytesToCopy > 0) {
                        dstBuf.put(pcmQueueBytes, pcmQueueOffset, bytesToCopy);
                        pcmQueueOffset += bytesToCopy;
                    }

                    boolean isLast = decoderDone && (pcmQueueBytes.length - pcmQueueOffset == 0);
                    int flags = isLast ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0;

                    encoder.queueInputBuffer(encoderInputBufferIndex, 0, bytesToCopy, lastPtsUs, flags);

                    if (isLast) {
                        encoderInputDone = true;
                    }
                }
            }

            // 4. Pull Encoder Output to OggWriter
            int encoderOutputBufferIndex = encoder.dequeueOutputBuffer(encoderBufferInfo, TIMEOUT_US);
            if (encoderOutputBufferIndex >= 0) {
                ByteBuffer encodedBuf = encoderOutputBuffers[encoderOutputBufferIndex];
                boolean isEOS = (encoderBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;

                if (encoderBufferInfo.size > 0 && (encoderBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    byte[] data = new byte[encoderBufferInfo.size];
                    encodedBuf.position(encoderBufferInfo.offset);
                    encodedBuf.get(data);

                    writer.writePacket(data, encoderBufferInfo.presentationTimeUs, isEOS);
                }

                encoder.releaseOutputBuffer(encoderOutputBufferIndex, false);

                if (isEOS) {
                    encoderDone = true;
                }
            } else if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                encoderOutputBuffers = encoder.getOutputBuffers();
            }
        }
    }

    private static class Resampler {
        private final int inRate;
        private final int inChannels;
        private final int outRate;
        private final int outChannels;

        public Resampler(int inRate, int inChannels, int outRate, int outChannels) {
            this.inRate = inRate;
            this.inChannels = inChannels;
            this.outRate = outRate;
            this.outChannels = outChannels;
        }

        public byte[] resample(ByteBuffer pcmBuf, int offset, int size) {
            pcmBuf.position(offset);
            pcmBuf.order(ByteOrder.LITTLE_ENDIAN);

            int numSamples = size / 2;
            short[] inputSamples = new short[numSamples];
            for (int i = 0; i < numSamples; i++) {
                inputSamples[i] = pcmBuf.getShort();
            }

            short[] channelMixed;
            if (inChannels == 2 && outChannels == 1) {
                int monoLength = numSamples / 2;
                channelMixed = new short[monoLength];
                for (int i = 0; i < monoLength; i++) {
                    int left = inputSamples[i * 2];
                    int right = inputSamples[i * 2 + 1];
                    channelMixed[i] = (short) ((left + right) / 2);
                }
            } else if (inChannels == 1 && outChannels == 2) {
                int stereoLength = numSamples * 2;
                channelMixed = new short[stereoLength];
                for (int i = 0; i < numSamples; i++) {
                    channelMixed[i * 2] = inputSamples[i];
                    channelMixed[i * 2 + 1] = inputSamples[i];
                }
            } else {
                channelMixed = inputSamples;
            }

            short[] resampled;
            if (inRate != outRate) {
                int inLength = channelMixed.length / outChannels;
                int outLength = (int) ((long) inLength * outRate / inRate);
                resampled = new short[outLength * outChannels];

                float scale = (float) inRate / outRate;
                for (int i = 0; i < outLength; i++) {
                    float inIdx = i * scale;
                    int idxLow = (int) inIdx;
                    int idxHigh = Math.min(idxLow + 1, inLength - 1);
                    float weight = inIdx - idxLow;

                    for (int c = 0; c < outChannels; c++) {
                        short sLow = channelMixed[idxLow * outChannels + c];
                        short sHigh = channelMixed[idxHigh * outChannels + c];
                        resampled[i * outChannels + c] = (short) ((1.0f - weight) * sLow + weight * sHigh);
                    }
                }
            } else {
                resampled = channelMixed;
            }

            byte[] outputBytes = new byte[resampled.length * 2];
            ByteBuffer outBuf = ByteBuffer.wrap(outputBytes);
            outBuf.order(ByteOrder.LITTLE_ENDIAN);
            for (short s : resampled) {
                outBuf.putShort(s);
            }
            return outputBytes;
        }
    }

    private static class OggOpusWriter {
        private final OutputStream out;
        private final int channels;
        private final int serialNum;
        private int pageSequenceNumber = 0;

        public OggOpusWriter(OutputStream out, int channels) {
            this.out = out;
            this.channels = channels;
            this.serialNum = new Random().nextInt();
        }

        public void writeHeaders() throws IOException {
            byte[] idHeader = new byte[19];
            System.arraycopy("OpusHead".getBytes(StandardCharsets.UTF_8), 0, idHeader, 0, 8);
            idHeader[8] = 1;
            idHeader[9] = (byte) channels;
            idHeader[10] = 0x38;
            idHeader[11] = 0x01;
            idHeader[12] = (byte) 0x80;
            idHeader[13] = (byte) 0xBB;
            idHeader[14] = 0x00;
            idHeader[15] = 0x00;
            idHeader[16] = 0x00;
            idHeader[17] = 0x00;
            idHeader[18] = 0x00;

            writeOggPage(idHeader, 0, true, false);

            byte[] tagsHeader = new byte[24];
            System.arraycopy("OpusTags".getBytes(StandardCharsets.UTF_8), 0, tagsHeader, 0, 8);
            tagsHeader[8] = 0x08;
            tagsHeader[9] = 0x00;
            tagsHeader[10] = 0x00;
            tagsHeader[11] = 0x00;
            System.arraycopy("wae-opus".getBytes(StandardCharsets.UTF_8), 0, tagsHeader, 12, 8);
            tagsHeader[20] = 0x00;
            tagsHeader[21] = 0x00;
            tagsHeader[22] = 0x00;
            tagsHeader[23] = 0x00;

            writeOggPage(tagsHeader, 0, false, false);
        }

        public void writePacket(byte[] packet, long ptsUs, boolean isEOS) throws IOException {
            long granulePos = (ptsUs * 48) / 1000;
            writeOggPage(packet, granulePos, false, isEOS);
        }

        private void writeOggPage(byte[] packet, long granulePos, boolean isBOS, boolean isEOS) throws IOException {
            int packetLen = packet.length;
            int numSegments = (packetLen + 254) / 255;
            if (numSegments == 0) {
                numSegments = 1;
            }

            byte[] fullHeader = new byte[27 + numSegments];
            fullHeader[0] = 0x4f; // 'O'
            fullHeader[1] = 0x67; // 'g'
            fullHeader[2] = 0x67; // 'g'
            fullHeader[3] = 0x53; // 'S'
            fullHeader[4] = 0;

            byte headerType = 0;
            if (isBOS) headerType |= 0x02;
            if (isEOS) headerType |= 0x04;
            fullHeader[5] = headerType;

            for (int i = 0; i < 8; i++) {
                fullHeader[6 + i] = (byte) ((granulePos >> (8 * i)) & 0xFF);
            }

            for (int i = 0; i < 4; i++) {
                fullHeader[14 + i] = (byte) ((serialNum >> (8 * i)) & 0xFF);
            }

            int seqNum = pageSequenceNumber++;
            for (int i = 0; i < 4; i++) {
                fullHeader[18 + i] = (byte) ((seqNum >> (8 * i)) & 0xFF);
            }

            fullHeader[22] = 0;
            fullHeader[23] = 0;
            fullHeader[24] = 0;
            fullHeader[25] = 0;

            fullHeader[26] = (byte) numSegments;

            int remaining = packetLen;
            for (int i = 0; i < numSegments; i++) {
                int segLen = Math.min(remaining, 255);
                fullHeader[27 + i] = (byte) segLen;
                remaining -= segLen;
            }

            int crc = calculateOggCrc(fullHeader, 0, fullHeader.length, 0);
            crc = calculateOggCrc(packet, 0, packet.length, crc);

            for (int i = 0; i < 4; i++) {
                fullHeader[22 + i] = (byte) ((crc >> (8 * i)) & 0xFF);
            }

            out.write(fullHeader);
            out.write(packet);
        }

        private static int calculateOggCrc(byte[] data, int offset, int length, int initialCrc) {
            int crc = initialCrc;
            for (int i = offset; i < offset + length; i++) {
                int val = data[i] & 0xFF;
                for (int j = 0; j < 8; j++) {
                    boolean bit = ((crc & 0x80000000) != 0) ^ ((val & 0x80) != 0);
                    crc <<= 1;
                    if (bit) {
                        crc ^= 0x04c11db7;
                    }
                    val <<= 1;
                }
            }
            return crc;
        }

        public void close() throws IOException {
            out.flush();
        }
    }
}
