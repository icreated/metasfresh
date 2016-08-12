package de.metas.ui.web.window.model;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import org.adempiere.ad.callout.api.ICalloutExecutor;
import org.adempiere.ad.callout.api.impl.CalloutExecutor;
import org.adempiere.ad.expression.api.IExpressionEvaluator.OnVariableNotFound;
import org.adempiere.ad.expression.api.ILogicExpression;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.util.Check;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.compiere.util.Evaluatee2;
import org.compiere.util.Evaluatees;
import org.slf4j.Logger;

import com.google.common.base.MoreObjects;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;

import de.metas.logging.LogManager;
import de.metas.ui.web.window.controller.Execution;
import de.metas.ui.web.window.descriptor.DocumentEntityDescriptor;
import de.metas.ui.web.window.descriptor.DocumentFieldDependencyMap;
import de.metas.ui.web.window.descriptor.DocumentFieldDependencyMap.DependencyType;
import de.metas.ui.web.window.descriptor.DocumentFieldDescriptor;
import de.metas.ui.web.window.exceptions.DocumentFieldNotFoundException;
import de.metas.ui.web.window_old.shared.datatype.LookupValue;

/*
 * #%L
 * metasfresh-webui-api
 * %%
 * Copyright (C) 2016 metas GmbH
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

public class Document
{
	private static final Logger logger = LogManager.getLogger(Document.class);

	public static final Document NULL = null;

	private final DocumentRepository documentRepository;
	private final DocumentEntityDescriptor entityDescriptor;
	private final int windowNo;

	private final Map<String, DocumentField> fieldsByName;
	private final DocumentField idField;

	private final Document _parentDocument;
	private final Map<String, IncludedDocumentsCollection> includedDocuments;

	private DocumentEvaluatee _evaluatee; // lazy

	private final ICalloutExecutor calloutExecutor;

	public Document(final DocumentRepository documentRepository, final DocumentEntityDescriptor entityDescriptor, final int windowNo, final Document parentDocument)
	{
		super();
		this.documentRepository = documentRepository;
		this.entityDescriptor = entityDescriptor;
		this.windowNo = windowNo;
		_parentDocument = parentDocument;

		//
		// Create document fields
		{
			final ImmutableMap.Builder<String, DocumentField> fieldsBuilder = ImmutableMap.builder();
			DocumentField idField = null;
			for (final DocumentFieldDescriptor fieldDescriptor : entityDescriptor.getFields())
			{
				final String name = fieldDescriptor.getName();
				final DocumentField field = new DocumentField(fieldDescriptor, this);
				fieldsBuilder.put(name, field);

				if (fieldDescriptor.isKey())
				{
					Check.assumeNull(idField, "Only one ID field shall exist but we found: {}, {}", idField, field); // shall no happen at this level
					idField = field;
				}
			}
			fieldsByName = fieldsBuilder.build();
			this.idField = idField;
		}

		//
		// Create included documents containers
		{
			final ImmutableMap.Builder<String, IncludedDocumentsCollection> includedDocuments = ImmutableMap.builder();
			for (final DocumentEntityDescriptor includedEntityDescriptor : entityDescriptor.getIncludedEntities())
			{
				final String detailId = includedEntityDescriptor.getDetailId();
				final IncludedDocumentsCollection includedDocumentsForDetailId = new IncludedDocumentsCollection(this, includedEntityDescriptor);
				includedDocuments.put(detailId, includedDocumentsForDetailId);
			}
			this.includedDocuments = includedDocuments.build();
		}

		calloutExecutor = new CalloutExecutor(Env.getCtx(), windowNo);
	}

	@Override
	public final String toString()
	{
		return MoreObjects.toStringHelper(this)
				.add("windowNo", windowNo)
				.add("fields", fieldsByName.values())
				.toString();
	}

	public int getWindowNo()
	{
		return windowNo;
	}

	/* package */DocumentRepository getDocumentRepository()
	{
		return documentRepository;
	}

	public DocumentEntityDescriptor getEntityDescriptor()
	{
		return entityDescriptor;
	}

	/* package */ Document getParentDocument()
	{
		return _parentDocument;
	}

	public Collection<DocumentField> getFields()
	{
		return fieldsByName.values();
	}

	public Set<String> getFieldNames()
	{
		return fieldsByName.keySet();
	}

	public boolean hasField(final String fieldName)
	{
		return fieldsByName.containsKey(fieldName);
	}

	/**
	 * @return field; never returns null
	 */
	public DocumentField getField(final String fieldName)
	{
		final DocumentField documentField = getFieldOrNull(fieldName);
		if (documentField == null)
		{
			throw new DocumentFieldNotFoundException(this, fieldName);
		}
		return documentField;
	}

	DocumentField getFieldOrNull(final String fieldName)
	{
		final DocumentField documentField = fieldsByName.get(fieldName);
		return documentField;
	}

	public int getDocumentId()
	{
		if (idField == null)
		{
			// TODO handle NO ID field or composed PK
			logger.warn("No ID field found for {}. Returning -1", this);
			return -1;
		}
		return idField.getValueAsInt(-1);
	}

	public boolean isNew()
	{
		// TODO: handle this state in a more reliable way
		return getDocumentId() < 0;
	}

	public Evaluatee2 asEvaluatee()
	{
		if (_evaluatee == null)
		{
			_evaluatee = new DocumentEvaluatee(this);
		}
		return _evaluatee;
	}

	private static final class DocumentEvaluatee implements Evaluatee2
	{
		private final Document _document;

		public DocumentEvaluatee(final Document document)
		{
			super();
			_document = document;
		}

		private Properties getCtx()
		{
			return Env.getCtx();
		}

		private Evaluatee2 getParent()
		{
			// NOTE: don't cache it because it might change (Document.getParentDocument() is not immutable!)
			final Document parentDocument = _document.getParentDocument();
			return parentDocument == null ? Evaluatees.empty() : parentDocument.asEvaluatee();
		}

		private boolean hasParent()
		{
			return _document.getParentDocument() != null;
		}

		private final DocumentField getDocumentFieldOrNull(final String name)
		{
			return _document.getFieldOrNull(name);
		}

		private final Set<String> getAvailableFieldNames()
		{
			return _document.getFieldNames();
		}

		@Override
		public boolean has_Variable(final String variableName)
		{
			if (variableName == null)
			{
				return false;
			}

			//
			// Environment variable
			if (variableName.startsWith("#"))                                                // Env, global var
			{
				return true;
			}
			else if (variableName.startsWith("$"))                                                // Env, global accounting var
			{
				return true;
			}

			//
			// Document field
			final DocumentField documentField = getDocumentFieldOrNull(variableName);
			if (documentField != null)
			{
				return true;
			}

			//
			// Check parent
			final Evaluatee2 parent = getParent();
			if (parent.has_Variable(variableName))
			{
				return true;
			}

			//
			// Not found
			if (logger.isTraceEnabled())
			{
				logger.trace("No document field {} found. Existing properties are: {}", variableName, getAvailableFieldNames());
			}
			return false;
		}

		@Override
		public String get_ValueAsString(final String variableName)
		{
			//
			// Environment variable
			if (variableName.startsWith("#"))                                                // Env, global var
			{
				return Env.getContext(getCtx(), variableName);
			}
			else if (variableName.startsWith("$"))                                                // Env, global accounting var
			{
				return Env.getContext(getCtx(), variableName);
			}

			//
			// Document field
			final DocumentField documentField = getDocumentFieldOrNull(variableName);
			if (documentField != null)
			{
				final String value = convertToString(documentField);
				if (value != null)
				{
					return value;
				}
			}

			//
			// Check parent
			{
				final Evaluatee2 parent = getParent();
				final String value = parent.get_ValueAsString(variableName);
				return value;
			}
		}

		@Override
		public String get_ValueOldAsString(final String variableName)
		{
			// TODO Auto-generated method stub
			return null;
		}

		/** Converts field value to {@link Evaluatee2} friendly string */
		private String convertToString(final DocumentField documentField)
		{
			final Object value = documentField.getValue();
			if (value == null)
			{
				if (hasParent())
				{
					return null; // advice the caller to ask the parent
				}

				// FIXME: hardcoded default to avoid a lot of warnings
				final String fieldName = documentField.getFieldName();
				if (fieldName.endsWith("_ID"))
				{
					return "-1";
				}

				// TODO: find some defaults?
				return null;
			}
			else if (value instanceof Boolean)
			{
				return DisplayType.toBooleanString((Boolean)value);
			}
			else if (value instanceof String)
			{
				return value.toString();
			}
			else if (value instanceof LookupValue)
			{
				final Object idObj = ((LookupValue)value).getId();
				return idObj == null ? null : idObj.toString().trim();
			}
			else if (value instanceof java.util.Date)
			{
				final java.util.Date valueDate = (java.util.Date)value;
				return Env.toString(valueDate);
			}
			else
			{
				return value.toString();
			}
		}
	}

	public void setValueFromJsonObject(final String fieldName, final Object value)
	{
		final DocumentField documentField = getField(fieldName);
		final Object valueOld = documentField.getValue();
		documentField.setValue(value);

		// Check if changed. If not, stop here.
		final Object valueNew = documentField.getValue();
		if (Objects.equals(valueOld, valueNew))
		{
			return;
		}

		// collect changed value
		final IDocumentFieldChangedEventCollector eventsCollector = Execution.getCurrentFieldChangedEventsCollector();
		eventsCollector.collectValueChanged(fieldName, documentField.getValueAsJsonObject(), () -> "direct set on Document");

		// Update all dependencies
		updateFieldsWhichDependsOn(fieldName, eventsCollector);

		// Callouts
		// TODO: find a way to collect events...
		calloutExecutor.execute(documentField.asCalloutField());

		// TODO: check if we can save it
	}

	/**
	 * Updates all dependencies for all fields (i.e.Mandatory, ReadOnly, Displayed properties etc)
	 */
	public void updateAllDependencies()
	{
		for (final DocumentField documentField : getFields())
		{
			updateFieldReadOnly(documentField);
			updateFieldMandatory(documentField);
			updateFieldDisplayed(documentField);
		}
	}

	private final void updateFieldReadOnly(final DocumentField documentField)
	{
		final DocumentFieldDescriptor fieldDescriptor = documentField.getDescriptor();

		final ILogicExpression readonlyLogic = fieldDescriptor.getReadonlyLogic();
		try
		{
			final boolean readonly = readonlyLogic.evaluate(asEvaluatee(), OnVariableNotFound.Fail);
			documentField.setReadonly(readonly);
		}
		catch (final Exception e)
		{
			logger.warn("Failed evaluating readonly logic {} for {}", readonlyLogic, documentField, e);
		}
	}

	private final void updateFieldMandatory(final DocumentField documentField)
	{
		final DocumentFieldDescriptor fieldDescriptor = documentField.getDescriptor();

		final ILogicExpression mandatoryLogic = fieldDescriptor.getMandatoryLogic();
		try
		{
			final boolean mandatory = mandatoryLogic.evaluate(asEvaluatee(), OnVariableNotFound.Fail);
			documentField.setMandatory(mandatory);
		}
		catch (final Exception e)
		{
			logger.warn("Failed evaluating mandatory logic {} for {}", mandatoryLogic, documentField, e);
		}
	}

	private final void updateFieldDisplayed(final DocumentField documentField)
	{
		final DocumentFieldDescriptor fieldDescriptor = documentField.getDescriptor();

		final ILogicExpression displayLogic = fieldDescriptor.getDisplayLogic();
		try
		{
			final boolean displayed = displayLogic.evaluate(asEvaluatee(), OnVariableNotFound.Fail);
			documentField.setDisplayed(displayed);
		}
		catch (final Exception e)
		{
			logger.warn("Failed evaluating display logic {} for {}", displayLogic, documentField, e);
		}
	}

	private final void updateFieldsWhichDependsOn(final String fieldName, final IDocumentFieldChangedEventCollector eventsCollector)
	{
		final DocumentFieldDependencyMap dependencies = getEntityDescriptor().getDependencies();
		dependencies.consumeForChangedFieldName(fieldName, (dependentFieldName, dependencyType) -> {
			final DocumentField dependentField = getFieldOrNull(dependentFieldName);
			if (dependentField == null)
			{
				logger.warn("Skip setting dependent propery {} because property value is missing", dependentFieldName);
				return;
			}

			final IDocumentFieldChangedEventCollector fieldEventsCollector = FieldChangedEventCollector.newInstance();
			updateDependentField(dependentField, fieldName, dependencyType, fieldEventsCollector);
			eventsCollector.collectFrom(fieldEventsCollector);

			for (final String dependentFieldNameLvl2 : fieldEventsCollector.getFieldNames())
			{
				updateFieldsWhichDependsOn(dependentFieldNameLvl2, eventsCollector);
			}

		});
	}

	private void updateDependentField(
			final DocumentField dependentField //
			, final String triggeringFieldName //
			, final DependencyType dependencyType //
			, final IDocumentFieldChangedEventCollector eventsCollector //
	)
	{
		if (DependencyType.ReadonlyLogic == dependencyType)
		{
			final boolean valueOld = dependentField.isReadonly();
			updateFieldReadOnly(dependentField);
			final boolean value = dependentField.isReadonly();

			if (value != valueOld)
			{
				final Supplier<String> reason = () -> "TriggeringField=" + triggeringFieldName + ", DependencyType=" + dependencyType
						+ ", ReadOnlyLogic=" + dependentField.getDescriptor().getReadonlyLogic();
				eventsCollector.collectReadonlyChanged(dependentField.getFieldName(), value, reason);
			}
		}
		else if (DependencyType.MandatoryLogic == dependencyType)
		{
			final boolean valueOld = dependentField.isMandatory();
			updateFieldMandatory(dependentField);
			final boolean value = dependentField.isMandatory();

			if (value != valueOld)
			{
				final Supplier<String> reason = () -> "TriggeringField=" + triggeringFieldName + ", DependencyType=" + dependencyType
						+ ", ReadOnlyLogic=" + dependentField.getDescriptor().getMandatoryLogic();
				eventsCollector.collectMandatoryChanged(dependentField.getFieldName(), value, reason);
			}
		}
		else if (DependencyType.DisplayLogic == dependencyType)
		{
			final boolean valueOld = dependentField.isDisplayed();
			updateFieldDisplayed(dependentField);
			final boolean value = dependentField.isDisplayed();

			if (value != valueOld)
			{
				final Supplier<String> reason = () -> "TriggeringField=" + triggeringFieldName + ", DependencyType=" + dependencyType
						+ ", ReadOnlyLogic=" + dependentField.getDescriptor().getDisplayLogic();
				eventsCollector.collectDisplayedChanged(dependentField.getFieldName(), value, reason);
			}
		}
		else if (DependencyType.LookupValues == dependencyType)
		{
			final Supplier<String> reason = () -> "TriggeringField=" + triggeringFieldName + ", DependencyType=" + dependencyType;
			eventsCollector.collectLookupValuesStaled(dependentField.getFieldName(), reason);
		}
		else
		{
			new AdempiereException("Unknown dependency type: " + dependencyType)
					.throwIfDeveloperModeOrLogWarningElse(logger);
		}
	}

	public List<LookupValue> getFieldLookupValues(final String fieldName)
	{
		return getField(fieldName).getLookupValues(this);
	}

	public List<LookupValue> getFieldLookupValuesForQuery(final String fieldName, final String query)
	{
		return getField(fieldName).getLookupValuesForQuery(this, query);

	}

	public Document getIncludedDocument(final String detailId, final DocumentId rowId)
	{
		final IncludedDocumentsCollection includedDocuments = getIncludedDocumentsCollection(detailId);
		return includedDocuments.getDocumentById(rowId);
	}

	public List<Document> getIncludedDocuments(final String detailId)
	{
		final IncludedDocumentsCollection includedDocuments = getIncludedDocumentsCollection(detailId);
		return includedDocuments.getDocuments();
	}

	private IncludedDocumentsCollection getIncludedDocumentsCollection(final String detailId)
	{
		final IncludedDocumentsCollection includedDocumentsForDetailId = includedDocuments.get(detailId);
		if (includedDocumentsForDetailId == null)
		{
			throw new IllegalArgumentException("detailId '" + detailId + "' not found for " + this);
		}
		return includedDocumentsForDetailId;
	}

	public Document createIncludedDocument(final String detailId)
	{
		final IncludedDocumentsCollection includedDocuments = getIncludedDocumentsCollection(detailId);
		return includedDocuments.createNewDocument();

	}

	/* package */ICalloutExecutor getCalloutExecutor()
	{
		return calloutExecutor;
	}

	public boolean isProcessed()
	{
		final DocumentField processedField = getFieldOrNull("Processed");
		if (processedField != null)
		{
			return processedField.getValueAsBoolean();
		}

		final Document parentDocument = getParentDocument();
		if (parentDocument != null)
		{
			return parentDocument.isProcessed();
		}

		return false;
	}
}
