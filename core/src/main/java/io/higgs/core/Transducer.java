package io.higgs.core;

import io.higgs.core.ssl.SSLContextFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.EmptyByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.Queue;

/**
 * Manipulates the current pipeline dynamically to switch protocols or enable
 * SSL or GZIP.
 */
public class Transducer extends ByteToMessageDecoder {
    private final Queue<ProtocolDetectorFactory> factories;
    private final Queue<InvokableMethod> methods;
    private Logger log = LoggerFactory.getLogger(getClass());
    private boolean detectSsl;
    private boolean detectGzip;
    private final SSLContextFactory sslCtx = new SSLContextFactory();

    public Transducer(boolean detectSsl, boolean detectGzip, Queue<ProtocolDetectorFactory> f,
                      Queue<InvokableMethod> methods) {
        this.detectSsl = detectSsl;
        this.detectGzip = detectGzip;
        factories = f;
        this.methods = methods;
    }

    @Override
    public void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in instanceof EmptyByteBuf) {
            return;
        }
        if (detectSsl) {
            // Will use the first five bytes to detect SSL.
            if (in.readableBytes() < 5) {
                return;
            }

            if (SslHandler.isEncrypted(in)) {
                enableSsl(ctx);
            }
        }
        if (detectGzip) {
            final int magic1 = in.getUnsignedByte(in.readerIndex());
            final int magic2 = in.getUnsignedByte(in.readerIndex() + 1);
            if (magic1 == 31 && magic2 == 139) {
                enableGzip(ctx);
            }
        }
        List<ProtocolDetectorFactory> protocols = new FixedSortedList<>(factories);
        Iterator<ProtocolDetectorFactory> it = protocols.iterator();
        boolean detectedProtocol = false;
        while (it.hasNext()) {
            ProtocolDetectorFactory codec = it.next();
            ProtocolDetector detector = codec.newProtocolDetector();
            if (detector.detected(ctx, in)) {
                detectedProtocol = true;
                ChannelPipeline p = ctx.pipeline();
                MessageHandler<?, ?> handler = detector.setupPipeline(p, ctx);
                handler.setMethods(methods);
                p.remove(this);
                break;
            }
        }
        if (!detectedProtocol) {
            log.warn("Unknown protocol. Discarding data and closing connection");
            //unknown protocol
            in.clear();
            ctx.close();
        }
    }

    private void enableSsl(ChannelHandlerContext ctx) {
        ChannelPipeline p = ctx.pipeline();
        sslCtx.addSSL(p, false, null);
        p.addLast("unificationA", new Transducer(false, detectGzip, factories, methods));
        p.remove(this);
    }

    private void enableGzip(ChannelHandlerContext ctx) {
        ChannelPipeline p = ctx.pipeline();
        p.addLast("gzipdeflater", ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP));
        p.addLast("gzipinflater", ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
        p.addLast("unificationB", new Transducer(detectSsl, false, factories, methods));
        p.remove(this);
    }

}
