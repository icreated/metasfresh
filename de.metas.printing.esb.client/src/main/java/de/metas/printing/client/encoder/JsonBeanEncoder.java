package de.metas.printing.client.encoder;

/*
 * #%L
 * de.metas.printing.esb.client
 * %%
 * Copyright (C) 2015 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */


import java.io.InputStream;

import org.codehaus.jackson.map.ObjectMapper;

import de.metas.printing.client.util.Util;

public class JsonBeanEncoder implements IBeanEnconder
{
	private final ObjectMapper mapper = new ObjectMapper(); // can reuse, share globally

	@Override
	public <T> T decodeString(final String text, Class<T> clazz)
	{
		try
		{
			return mapper.readValue(text, clazz);
		}
		catch (Exception e)
		{
			throw new RuntimeException("Cannot parse text: " + text, e);
		}
	}

	@Override
	public <T> T decodeStream(final InputStream in, Class<T> clazz)
	{
		if (in == null)
		{
			throw new IllegalArgumentException("'in' input stream is null");
		}

		String str = null;
		try
		{
			str = Util.toString(in);
			return mapper.readValue(str, clazz);
		}
		catch (Exception e)
		{
			throw new RuntimeException("Cannot parse input stream: " + str, e);
		}
	}

	@Override
	public <T> T decodeBytes(byte[] data, Class<T> clazz)
	{
		try
		{
			return mapper.readValue(data, clazz);
		}
		catch (Exception e)
		{
			throw new RuntimeException("Cannot parse bytes: " + data, e);
		}
	}

	@Override
	public <T> byte[] encode(T object)
	{
		try
		{
			return mapper.writeValueAsBytes(object);
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}

	@Override
	public String getContentType()
	{
		return "application/json";
	}
}
