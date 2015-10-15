package org.adempiere.ad.trx.processor.api;

/*
 * #%L
 * de.metas.adempiere.adempiere.base
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


import org.adempiere.exceptions.AdempiereException;

/**
 * An {@link ITrxItemProcessorExecutor}'s exception handler which fails on first error.
 * 
 * @author tsa
 * 
 */
public final class FailTrxItemExceptionHandler implements ITrxItemExceptionHandler
{
	public static final FailTrxItemExceptionHandler instance = new FailTrxItemExceptionHandler();

	private FailTrxItemExceptionHandler()
	{
		super();
	}

	private final void fail(final Exception e, Object item)
	{
		if (e instanceof AdempiereException)
		{
			final AdempiereException aex = (AdempiereException)e;
			throw aex;
		}
		else
		{
			throw new AdempiereException(e.getLocalizedMessage(), e);
		}
	}

	@Override
	public void onNewChunkError(Exception e, Object item)
	{
		fail(e, item);
	}

	@Override
	public void onItemError(Exception e, Object item)
	{
		fail(e, item);
	}

	@Override
	public void onCompleteChunkError(Exception e)
	{
		final Object item = null;
		fail(e, item);
	}

	@Override
	public void onCommitChunkError(Exception e)
	{
		final Object item = null;
		fail(e, item);
	}

	@Override
	public void onCancelChunkError(Exception e)
	{
		final Object item = null;
		fail(e, item);
	}
}
