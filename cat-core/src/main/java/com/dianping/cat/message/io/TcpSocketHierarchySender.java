package com.dianping.cat.message.io;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

import org.codehaus.plexus.logging.LogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;

import com.dianping.cat.message.spi.MessageCodec;
import com.dianping.cat.message.spi.MessageQueue;
import com.dianping.cat.message.spi.MessageStatistics;
import com.dianping.cat.message.spi.MessageTree;
import com.site.lookup.annotation.Inject;

public class TcpSocketHierarchySender extends Thread implements MessageSender, LogEnabled {
	@Inject
	private MessageCodec m_codec;

	@Inject
	private MessageQueue m_queue;

	@Inject
	private MessageStatistics m_statistics;

	private List<InetSocketAddress> m_serverAddresses;

	private ChannelManager m_manager;

	private Logger m_logger;

	private transient boolean m_active;

	@Override
	public void enableLogging(Logger logger) {
		m_logger = logger;
	}

	@Override
	public void initialize() {
		m_manager = new ChannelManager(m_logger, m_serverAddresses);

		this.setName("TcpSocketHierarchySender");
		this.start();
		m_manager.start();
	}

	@Override
	public void run() {
		m_active = true;

		while (m_active) {
			try {
				MessageTree tree = m_queue.poll();

				if (tree != null) {
					sendInternal(tree);
					tree.setMessage(null);
				}
			} catch (Throwable t) {
				m_logger.error("Error when sending message over TCP socket!", t);
			}
		}

		m_manager.releaseAll();
	}

	@Override
	public void send(MessageTree tree) {
		boolean result = m_queue.offer(tree);

		if (!result) {
			if (m_statistics != null) {
				m_statistics.onOverflowed(tree);
			}

			m_logger.error("Message queue is full in tcp socket sender!");
		}
	}

	private void sendInternal(MessageTree tree) {
		ChannelFuture future = m_manager.getChannel();

		if (future != null && future.getChannel().isOpen()) {
			ChannelBuffer buf = ChannelBuffers.dynamicBuffer(10 * 1024); // 10K

			m_codec.encode(tree, buf);

			int size = buf.readableBytes();

			future.getChannel().write(buf);

			if (m_statistics != null) {
				m_statistics.onBytes(size);
			}
		}
	}

	public void setCodec(MessageCodec codec) {
		m_codec = codec;
	}

	public void setServerAddresses(List<InetSocketAddress> serverAddresses) {
		m_serverAddresses = serverAddresses;
	}

	@Override
	public void shutdown() {
		m_active = false;
		m_manager.shutdown();
	}

	static class ChannelManager extends Thread {
		private List<InetSocketAddress> m_serverAddresses;

		private List<ChannelFuture> m_futures;

		private ClientBootstrap m_bootstrap;

		private ChannelFuture m_activeFuture;

		private int m_activeIndex;

		private Logger m_logger;

		private ChannelFuture m_lastFuture;

		private boolean m_enabled = true;

		public ChannelManager(Logger logger, List<InetSocketAddress> serverAddresses) {
			int len = serverAddresses.size();

			m_logger = logger;
			m_serverAddresses = serverAddresses;
			m_futures = new ArrayList<ChannelFuture>(Collections.<ChannelFuture> nCopies(len, null));

			ChannelFactory factory = new NioClientSocketChannelFactory(Executors.newFixedThreadPool(10),
			      Executors.newFixedThreadPool(10));
	
			ClientBootstrap bootstrap = new ClientBootstrap(factory);

			bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
				public ChannelPipeline getPipeline() {
					return Channels.pipeline(new MyHandler(m_logger));
				}
			});

			bootstrap.setOption("tcpNoDelay", true);
			bootstrap.setOption("keepAlive", true);

			m_bootstrap = bootstrap;

			for (int i = 0; i < len; i++) {
				ChannelFuture future = createChannel(i);

				if (future != null) {
					m_activeFuture = future;
					m_activeIndex = i;
					break;
				}
			}
		}

		ChannelFuture createChannel(int index) {
			InetSocketAddress address = m_serverAddresses.get(index);
			ChannelFuture future = m_bootstrap.connect(address);

			future.awaitUninterruptibly();

			if (!future.isSuccess()) {
				future.getChannel().getCloseFuture().awaitUninterruptibly();
				m_logger.error("Error when try to connecting to " + address + ", message: " + future.getCause());
				return null;
			} else {
				m_logger.info("Connected to CAT server at " + address);
				return future;
			}
		}

		public ChannelFuture getChannel() {
			if (m_lastFuture != null && m_lastFuture != m_activeFuture) {
				m_lastFuture.getChannel().close();
				m_lastFuture = null;
			}

			return m_activeFuture;
		}

		public void releaseAll() {
			for (ChannelFuture future : m_futures) {
				if (future != null) {
					future.getChannel().getCloseFuture().awaitUninterruptibly();
				}
			}

			m_bootstrap.getFactory().releaseExternalResources();
			m_futures = null;
		}

		@Override
		public void run() {
			try {
				while (m_enabled) {
					if (m_activeFuture != null && !m_activeFuture.getChannel().isOpen()) {
						m_activeIndex = m_serverAddresses.size();
					}

					for (int i = 0; i < m_activeIndex; i++) {
						ChannelFuture future = createChannel(i);

						if (future != null) {
							m_lastFuture = m_activeFuture;
							m_activeFuture = future;
							m_activeIndex = i;
							break;
						}
					}

					Thread.sleep(2 * 1000); // check every 2 seconds
				}
			} catch (InterruptedException e) {
				// ignore
			}
		}

		public void shutdown() {
			m_enabled = false;
		}
	}

	static class MyHandler extends SimpleChannelHandler {
		private Logger m_logger;

		public MyHandler(Logger logger) {
			m_logger = logger;
		}

		@Override
		public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
			m_logger.warn("Channel disconnected by remote address: " + e.getChannel().getRemoteAddress());
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
			e.getChannel().close();
		}

		@Override
		public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
			ChannelBuffer buf = (ChannelBuffer) e.getMessage();

			while (buf.readable()) {
				// TODO do something here
				System.out.println((char) buf.readByte());
				System.out.flush();
			}
		}
	}
}
