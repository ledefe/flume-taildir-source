/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.flume.source.taildir;

import static org.apache.flume.source.taildir.TaildirSourceConfigurationConstants.BYTE_OFFSET_HEADER_KEY;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.apache.flume.Event;
import org.apache.flume.event.EventBuilder;
import org.apache.flume.interceptor.TimestampInterceptor.Constants;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

public class TailFile {
	private static final Logger logger = LoggerFactory.getLogger(TailFile.class);

	private static final byte BYTE_NL = (byte) 10;
	private static final byte BYTE_CR = (byte) 13;

	private static final int BUFFER_SIZE = 1;// 8192;
	private static final int NEED_READING = -1;

	private RandomAccessFile raf;
	private final String path;
	private final long inode;
	private long pos;
	private long lastUpdated;
	private boolean needTail;
	private final Map<String, String> headers;
	private byte[] buffer;
	private byte[] oldBuffer;
	private int bufferPos;
	private long lineReadPos;
	public static final String CHARSET_DFLT = "UTF-8";

	// 是否启动前缀匹配
	private boolean multilineEnable;

	private String pattern;

	private static final String DEFAULT_TIMESTAMPPATTERN = "(^[0-9]{4}-(((0[13578]|(10|12))-(0[1-9]|[1-2][0-9]|3[0-1]))|(02-(0[1-9]|[1-2][0-9]))|((0[469]|11)-(0[1-9]|[1-2][0-9]|30)))(\\ |T)(([0-1]?[0-9])|([2][0-3])):([0-5]?[0-9]):(([0-5]?[0-9]))\\.[0-9]{3}(((\\+|-)[0-9]{4})?))([\\s\\S]*)";

	private String timestampPattern;

	private boolean includeLineBreak = true;

	public TailFile(File file, Map<String, String> headers, long inode, long pos, Map<String, String> extension)
			throws IOException {

		this.raf = new RandomAccessFile(file, "r");
		if (pos > 0) {
			raf.seek(pos);
			lineReadPos = pos;
		}
		this.path = file.getAbsolutePath();
		this.inode = inode;
		this.pos = pos;
		this.lastUpdated = 0L;
		this.needTail = true;
		this.headers = headers;
		this.oldBuffer = new byte[0];
		this.bufferPos = NEED_READING;
		if (extension != null) {
			this.multilineEnable = true;
			this.pattern = extension.get(TaildirSourceConfigurationConstants.EXTENSION_MULTILINE_PATTERN_KEY);
			this.timestampPattern = extension.get(TaildirSourceConfigurationConstants.EXTENSION_TIMESTAMP_PATTERN_KEY);
			if (StringUtils.isEmpty(timestampPattern)) {
				this.timestampPattern = DEFAULT_TIMESTAMPPATTERN;
			}
		} else {
			this.multilineEnable = true;
			this.pattern = "";
			// 默认iso date格式
			this.timestampPattern = DEFAULT_TIMESTAMPPATTERN;
		}

		logger.info("init pattern: " + pattern + ", inode: " + inode + ", path: " + path + ",timestampPattern:"
				+ timestampPattern);

	}

	public RandomAccessFile getRaf() {
		return raf;
	}

	public String getPath() {
		return path;
	}

	public long getInode() {
		return inode;
	}

	public long getPos() {
		return pos;
	}

	public long getLastUpdated() {
		return lastUpdated;
	}

	public boolean needTail() {
		return needTail;
	}

	public Map<String, String> getHeaders() {
		return headers;
	}

	public long getLineReadPos() {
		return lineReadPos;
	}

	public void setPos(long pos) {
		this.pos = pos;
	}

	public void setLastUpdated(long lastUpdated) {
		this.lastUpdated = lastUpdated;
	}

	public void setNeedTail(boolean needTail) {
		this.needTail = needTail;
	}

	public void setLineReadPos(long lineReadPos) {
		this.lineReadPos = lineReadPos;
	}

	public boolean updatePos(String path, long inode, long pos) throws IOException {
		// 支持文件名重名后不采集
		// if (this.inode == inode && this.path.equals(path)) {
		if (this.inode == inode) {
			setPos(pos);
			updateFilePos(pos);
			logger.info("Updated position, file: " + path + ", inode: " + inode + ", pos: " + pos);
			return true;
		}
		return false;
	}

	public void updateFilePos(long pos) throws IOException {
		raf.seek(pos);
		lineReadPos = pos;
		bufferPos = NEED_READING;
		oldBuffer = new byte[0];
	}

	public List<Event> readEvents(int numEvents, boolean backoffWithoutNL, boolean addByteOffset) throws IOException {
		List<Event> events = Lists.newLinkedList();
		for (int i = 0; i < numEvents; i++) {
			Event event = readEvent(backoffWithoutNL, addByteOffset);
			if (event == null) {
				break;
			}
			events.add(event);
		}
		return events;
	}

	/**
	 * 读取事件
	 * 
	 * @param backoffWithoutNL
	 * @param addByteOffset
	 * @return
	 * @throws IOException
	 */
	private Event readEvent(boolean backoffWithoutNL, boolean addByteOffset) throws IOException {

		Long posTmp = getLineReadPos();

		LineResult line = readLine();

		if (line == null) {
			return null;
		}

		// 未读到结束符,则回退，下次再取
		if (checkEndFlag(backoffWithoutNL, line.lineSepInclude)) {

			updateFilePos(posTmp);

			return null;
		}

		String lineBody = new String(line.line, CHARSET_DFLT);

		StringBuffer lineFullBody = new StringBuffer().append(lineBody);

		long time = getTimestamp(lineBody);

		// 有匹配到，才会执行多行合并操作
		if (multilineEnable && !StringUtils.isEmpty(pattern)) {
			// 行匹配
			if (lineBody.matches(pattern)) {
				// 合并多行内容
				while (true) {

					Long curPosTmp = getLineReadPos();

					LineResult lineTemp = readLine();

					// 下次再尝试
					if (lineTemp == null) {
						updateFilePos(posTmp);
						return null;
					}

					String lineTempBody = new String(lineTemp.line, CHARSET_DFLT);

					// 行匹配到下一行开始，则跳出
					if (lineTempBody.matches(pattern)) {
						// 需要回退
						updateFilePos(curPosTmp);
						break;
					}

					// 未读到结束符,则回退，下次再取
					if (checkEndFlag(backoffWithoutNL, line.lineSepInclude)) {
						updateFilePos(posTmp);
						return null;
					}

					if (includeLineBreak) {
						lineFullBody.append("\n").append(lineTempBody);
					} else {
						lineFullBody.append(lineTempBody);
					}
				}
			}
		}

		Event event = EventBuilder.withBody(lineFullBody.toString(), Charset.forName(CHARSET_DFLT));
		event.getHeaders().put(Constants.TIMESTAMP, Long.toString(time));
		if (addByteOffset == true) {
			event.getHeaders().put(BYTE_OFFSET_HEADER_KEY, posTmp.toString());
		}
		return event;
	}

	private long getTimestamp(String lineBody) {

		DateTime dateTime = DateTime.now();

		if (StringUtils.isNotEmpty(lineBody) && StringUtils.isNotEmpty(timestampPattern)) {

			Pattern pattern = Pattern.compile(timestampPattern);

			Matcher matcher = pattern.matcher(lineBody);

			if (matcher.find()) {
				if (matcher.groupCount() > 1) {

					String dateStr = matcher.group(1);
					int dateLeng = dateStr.length();
					try {
						dateTime = DateTime.parse(dateStr);
					} catch (Exception e) {
						try {
							if (dateLeng == 19) {
								dateTime = DateTime.parse(dateStr, DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss"));
							} else if (dateLeng == 23) {
								dateTime = DateTime.parse(dateStr,
										DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS"));
							} else if (dateStr.length() >= 28) {
								dateTime = DateTime.parse(dateStr,
										DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSSZ"));
							}
						} catch (Exception e2) {
							// ignore
						}
					}
				}

			}
		}
		return dateTime.getMillis();
	}

	private void readFile() throws IOException {
		if ((raf.length() - raf.getFilePointer()) < BUFFER_SIZE) {
			buffer = new byte[(int) (raf.length() - raf.getFilePointer())];
		} else {
			buffer = new byte[BUFFER_SIZE];
		}
		raf.read(buffer, 0, buffer.length);
		bufferPos = 0;
	}

	private byte[] concatByteArrays(byte[] a, int startIdxA, int lenA, byte[] b, int startIdxB, int lenB) {
		byte[] c = new byte[lenA + lenB];
		System.arraycopy(a, startIdxA, c, 0, lenA);
		System.arraycopy(b, startIdxB, c, lenA, lenB);
		return c;
	}

	public LineResult readLine() throws IOException {

		LineResult lineResult = null;

		while (true) {

			if (bufferPos == NEED_READING) {

				if (raf.getFilePointer() < raf.length()) {

					readFile();

				} else {

					if (oldBuffer.length > 0) {

						lineResult = new LineResult(false, oldBuffer);
						oldBuffer = new byte[0];
						// 设置新的偏移量
						// setLineReadPos(lineReadPos + lineResult.line.length);

					}

					break;
				}
			}

			for (int i = bufferPos; i < buffer.length; i++) {

				if (buffer[i] == BYTE_NL) {

					int oldLen = oldBuffer.length;

					// Don't copy last byte(NEW_LINE)
					int lineLen = i - bufferPos;

					// For windows, check for CR
					// if (i > 0 && buffer[i - 1] == BYTE_CR) {
					// lineLen -= 1;
					// } else if (oldBuffer.length > 0 &&
					// oldBuffer[oldBuffer.length - 1] == BYTE_CR) {
					// oldLen -= 1;
					// }
					// 拼接
					lineResult = new LineResult(true,
							concatByteArrays(oldBuffer, 0, oldLen, buffer, bufferPos, lineLen));

					// 设置最新的偏移量
					setLineReadPos(lineReadPos + (oldBuffer.length + (i - bufferPos + 1)));

					oldBuffer = new byte[0];

					if (i + 1 < buffer.length) {

						bufferPos = i + 1;

					} else {

						bufferPos = NEED_READING;

					}

					break;
				}

			}
			if (lineResult != null) {
				break;
			}
			// NEW_LINE not showed up at the end of the buffer
			oldBuffer = concatByteArrays(oldBuffer, 0, oldBuffer.length, buffer, bufferPos, buffer.length - bufferPos);
			bufferPos = NEED_READING;
		}
		return lineResult;
	}

	public void close() {
		try {
			raf.close();
			raf = null;
			long now = System.currentTimeMillis();
			setLastUpdated(now);
		} catch (IOException e) {
			logger.error("Failed closing file: " + path + ", inode: " + inode, e);
		}
	}

	private class LineResult {
		final boolean lineSepInclude;
		final byte[] line;

		public LineResult(boolean lineSepInclude, byte[] line) {
			super();
			this.lineSepInclude = lineSepInclude;
			this.line = line;
		}
	}

	private boolean checkEndFlag(boolean backoffWithoutNL, boolean lineSepInclude) throws IOException {

		if (backoffWithoutNL && !lineSepInclude) {

			logger.info("Backing off in file without newline: " + path + ", inode: " + inode + ", pos: "
					+ raf.getFilePointer());
			return true;

		}

		return false;
	}
}
