package org.genecash.garagedoor;

import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

public class CustomFormatter extends Formatter {

	@Override
	public String format(LogRecord record) {

		// just the date and the message
		Date date = new Date(record.getMillis());
		return date.toString() + " " + formatMessage(record) + "\n";
	}
}
