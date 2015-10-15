package de.metas.adempiere.form;

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


import org.adempiere.ad.service.IDeveloperModeBL;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.util.Check;
import org.adempiere.util.Services;
import org.adempiere.util.lang.ObjectUtils;
import org.compiere.util.CLogger;

public abstract class AbstractClientUIInvoker implements IClientUIInvoker
{
	// Services
	protected static final transient CLogger logger = CLogger.getCLogger(AbstractClientUIInvoker.class);
	protected final transient IDeveloperModeBL developerModeBL = Services.get(IDeveloperModeBL.class);
	private final transient IClientUIInstance clientUI;

	private boolean invokeLater;
	private boolean longOperation;
	private OnFail onFail = OnFail.ShowErrorPopup;
	private Object parentComponent;
	private int parentWindowNo;
	private Runnable runnable = null;

	public AbstractClientUIInvoker(final IClientUIInstance clientUI)
	{
		super();
		Check.assumeNotNull(clientUI, "clientUI not null");
		this.clientUI = clientUI;
	}
	
	@Override
	public String toString()
	{
		return ObjectUtils.toString(this);
	}

	@Override
	public final void invoke(final Runnable runnable)
	{
		setRunnable(runnable);
		invoke();
	}

	@Override
	public final void invoke()
	{
		Runnable runnableWrapped = getRunnable();
		Check.assumeNotNull(runnableWrapped, "runnable shall be configured");

		// Wrap to Long Operation
		// NOTE: this needs to be wrapped BEFORE InvokeLater because invoke later will be executed asynchronously
		if (isLongOperation())
		{
			runnableWrapped = asLongOperationRunnable(runnableWrapped);
		}

		runnableWrapped = asExceptionHandledRunnable(runnableWrapped);

		if (isInvokeLater())
		{
			runnableWrapped = asInvokeLaterRunnable(runnableWrapped);
		}

		//
		// Execute it
		runnableWrapped.run();
	}

	protected abstract Runnable asInvokeLaterRunnable(final Runnable runnable);

	protected abstract Runnable asLongOperationRunnable(final Runnable runnable);

	private final Runnable asExceptionHandledRunnable(final Runnable runnable)
	{
		final OnFail onFail = getOnFail();
		if (OnFail.ThrowException == onFail)
		{
			return runnable;
		}

		return new Runnable()
		{
			@Override
			public String toString()
			{
				return "ExceptionHandled[" + runnable + "]";
			}

			@Override
			public void run()
			{
				try
				{
					runnable.run();
				}
				catch (Exception e)
				{
					handleException(e);
				}
			}
		};
	}

	protected final void handleException(final Exception e)
	{
		final OnFail onFail = getOnFail();
		if (OnFail.ThrowException == onFail)
		{
			throw AdempiereException.wrapIfNeeded(e);
		}
		else if (OnFail.ShowErrorPopup == onFail)
		{
			clientUI.error(getParentWindowNo(), e);
		}
		else if (OnFail.SilentlyIgnore == onFail)
		{
			// Ignore it silently. Don't do logging.
			// logger.log(Level.WARNING, "Got error while running: " + runnable + ". Ignored.", e);
			return;
		}
		// Fallback: throw the exception
		else
		{
			throw AdempiereException.wrapIfNeeded(e);
		}
	}

	@Override
	public final IClientUIInvoker setInvokeLater(final boolean invokeLater)
	{
		this.invokeLater = invokeLater;
		return this;
	}

	protected final boolean isInvokeLater()
	{
		return invokeLater;
	}

	@Override
	public final IClientUIInvoker setLongOperation(final boolean longOperation)
	{
		this.longOperation = longOperation;
		return this;
	}

	protected final boolean isLongOperation()
	{
		return longOperation;
	}

	@Override
	public final IClientUIInvoker setOnFail(OnFail onFail)
	{
		Check.assumeNotNull(onFail, "onFail not null");
		this.onFail = onFail;
		return this;
	}

	private final OnFail getOnFail()
	{
		return onFail;
	}

	@Override
	public abstract IClientUIInvoker setParentComponent(final Object parentComponent);

	@Override
	public abstract IClientUIInvoker setParentComponentByWindowNo(final int windowNo);

	protected final void setParentComponent(final int windowNo, final Object component)
	{
		this.parentWindowNo = windowNo;
		this.parentComponent = component;
	}

	protected final Object getParentComponent()
	{
		return parentComponent;
	}

	protected final int getParentWindowNo()
	{
		return parentWindowNo;
	}

	@Override
	public final IClientUIInvoker setRunnable(final Runnable runnable)
	{
		this.runnable = runnable;
		return this;
	}

	private final Runnable getRunnable()
	{
		return runnable;
	}
}
