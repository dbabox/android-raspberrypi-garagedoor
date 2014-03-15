package org.genecash.garagedoor;

import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

public class CustomFormatter extends Formatter {

	@Override
	public String format(LogRecord record) {
		StringBuffer sb = new StringBuffer();

		// just the date and the message
		Date date = new Date(record.getMillis());
		sb.append(date.toString());
		sb.append(" ");
		sb.append(formatMessage(record));
		sb.append("\n");

		return sb.toString();
	}

}
