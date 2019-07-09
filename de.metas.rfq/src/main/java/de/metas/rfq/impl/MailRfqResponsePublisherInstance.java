package de.metas.rfq.impl;

import java.sql.Timestamp;

import org.adempiere.archive.api.IArchiveEventManager;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.service.ClientId;
import org.adempiere.service.IClientDAO;
import org.compiere.model.I_AD_User;

import de.metas.document.archive.model.I_AD_Archive;
import de.metas.document.archive.model.X_C_Doc_Outbound_Log_Line;
import de.metas.document.archive.spi.impl.DefaultModelArchiver;
import de.metas.email.EMail;
import de.metas.email.EMailAddress;
import de.metas.email.EMailCustomType;
import de.metas.email.EMailSentStatus;
import de.metas.email.IMailBL;
import de.metas.email.mailboxes.ClientEMailConfig;
import de.metas.email.mailboxes.UserEMailConfig;
import de.metas.email.templates.MailTemplateId;
import de.metas.email.templates.MailTextBuilder;
import de.metas.rfq.IRfqDAO;
import de.metas.rfq.RfQResponsePublisherRequest;
import de.metas.rfq.RfQResponsePublisherRequest.PublishingType;
import de.metas.rfq.exceptions.RfQPublishException;
import de.metas.rfq.model.I_C_RfQResponse;
import de.metas.rfq.model.I_C_RfQ_Topic;
import de.metas.util.Services;

/*
 * #%L
 * de.metas.rfq
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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

/* package */class MailRfqResponsePublisherInstance
{
	public static final MailRfqResponsePublisherInstance newInstance()
	{
		return new MailRfqResponsePublisherInstance();
	}

	// services
	private final transient IRfqDAO rfqDAO = Services.get(IRfqDAO.class);
	private final transient IMailBL mailBL = Services.get(IMailBL.class);
	private final transient IArchiveEventManager archiveEventManager = Services.get(IArchiveEventManager.class);
	private final transient IClientDAO clientsRepo = Services.get(IClientDAO.class);

	public enum RfQReportType
	{
		Invitation, InvitationWithoutQtyRequired, Won, Lost,
	}

	private MailRfqResponsePublisherInstance()
	{
		super();
	}

	public void publish(final RfQResponsePublisherRequest request)
	{
		final RfQReportType rfqReportType = getRfQReportType(request);
		publish(request, rfqReportType);

	}

	public void publish(final RfQResponsePublisherRequest request, final RfQReportType rfqReportType)
	{
		try
		{
			publish0(request, rfqReportType);
		}
		catch (Exception e)
		{
			throw RfQPublishException.wrapIfNeeded(e)
					.setRequest(request);
		}
	}

	public void publish0(final RfQResponsePublisherRequest request, final RfQReportType rfqReportType)
	{
		final I_C_RfQResponse rfqResponse = request.getC_RfQResponse();

		//
		// Check and get the user's mail where we will send the email to
		final I_AD_User userTo = rfqResponse.getAD_User();
		if (userTo == null)
		{
			throw new RfQPublishException(request, "@NotFound@ @AD_User_ID@");
		}
		final EMailAddress userToEmail = EMailAddress.ofNullableString(userTo.getEMail());
		if (userToEmail == null)
		{
			throw new RfQPublishException(request, "@NotFound@ @AD_User_ID@ @Email@ - " + userTo);
		}

		//
		final MailTextBuilder mailTextBuilder = createMailTextBuilder(rfqResponse, rfqReportType);

		//
		final String subject = mailTextBuilder.getMailHeader();
		final String message = mailTextBuilder.getFullMailText();
		final DefaultModelArchiver archiver = DefaultModelArchiver.of(rfqResponse)
				.setAD_PrintFormat_ID(getAD_PrintFormat_ID(rfqResponse, rfqReportType));
		final I_AD_Archive pdfArchive = archiver.archive();
		final byte[] pdfData = archiver.getPdfData();

		final ClientId adClientId = ClientId.ofRepoId(rfqResponse.getAD_Client_ID());
		final ClientEMailConfig tenantEmailConfig = clientsRepo.getEMailConfigById(adClientId);
		
		//
		// Send it
		final EMail email = mailBL.createEMail(
				tenantEmailConfig, //
				(EMailCustomType)null, // mailCustomType
				(UserEMailConfig)null, // from
				userToEmail, // to
				subject, // subject
				message,  // message
				mailTextBuilder.isHtml()); // html
		email.addAttachment("RfQ_" + rfqResponse.getC_RfQResponse_ID() + ".pdf", pdfData);
		final EMailSentStatus emailSentStatus = email.send();

		//
		// Fire mail sent/not sent event (even if there were some errors)
		{
			final EMailAddress from = email.getFrom();
			final EMailAddress to = email.getTo();
			archiveEventManager.fireEmailSent(
					pdfArchive, // archive
					X_C_Doc_Outbound_Log_Line.ACTION_EMail, // action
					(UserEMailConfig)null, // user
					from, // from
					to, // to
					(EMailAddress)null, // cc
					(EMailAddress)null, // bcc
					emailSentStatus.getSentMsg() // status
			);
		}

		//
		// Update RfQ response (if success)
		if (emailSentStatus.isSentOK())
		{
			rfqResponse.setDateInvited(new Timestamp(System.currentTimeMillis()));
			InterfaceWrapperHelper.save(rfqResponse);
		}
		else
		{
			throw new RfQPublishException(request, emailSentStatus.getSentMsg());
		}
	}

	public RfQReportType getRfQReportType(final RfQResponsePublisherRequest request)
	{
		final I_C_RfQResponse rfqResponse = request.getC_RfQResponse();
		final PublishingType publishingType = request.getPublishingType();
		if (publishingType == PublishingType.Invitation)
		{
			if (rfqDAO.hasQtyRequiered(rfqResponse))
			{
				return RfQReportType.Invitation;
			}
			else
			{
				return RfQReportType.InvitationWithoutQtyRequired;
			}
		}
		else if (publishingType == PublishingType.Close)
		{
			if (rfqDAO.hasSelectedWinnerLines(rfqResponse))
			{
				return RfQReportType.Won;
			}
			else
			{
				return RfQReportType.Lost;
			}
		}
		else
		{
			throw new AdempiereException("@Invalid@ @PublishingType@: " + publishingType);
		}
	}

	private int getAD_PrintFormat_ID(final I_C_RfQResponse rfqResponse, final RfQReportType rfqReportType)
	{
		final I_C_RfQ_Topic rfqTopic = rfqResponse.getC_RfQ().getC_RfQ_Topic();

		if (rfqReportType == RfQReportType.Invitation)
		{
			return rfqTopic.getRfQ_Invitation_PrintFormat_ID();
		}
		else if (rfqReportType == RfQReportType.InvitationWithoutQtyRequired)
		{
			return rfqTopic.getRfQ_InvitationWithoutQty_PrintFormat_ID();
		}
		else if (rfqReportType == RfQReportType.Won)
		{
			return rfqTopic.getRfQ_Win_PrintFormat_ID();
		}
		else if (rfqReportType == RfQReportType.Lost)
		{
			return rfqTopic.getRfQ_Lost_PrintFormat_ID();
		}
		else
		{
			throw new AdempiereException("@Invalid@ @Type@: " + rfqReportType);
		}
	}

	private MailTextBuilder createMailTextBuilder(final I_C_RfQResponse rfqResponse, final RfQReportType rfqReportType)
	{
		final I_C_RfQ_Topic rfqTopic = rfqResponse.getC_RfQ().getC_RfQ_Topic();

		final MailTextBuilder mailTextBuilder;
		if (rfqReportType == RfQReportType.Invitation)
		{
			mailTextBuilder = mailBL.newMailTextBuilder(MailTemplateId.ofRepoId(rfqTopic.getRfQ_Invitation_MailText_ID()));
		}
		else if (rfqReportType == RfQReportType.InvitationWithoutQtyRequired)
		{
			mailTextBuilder = mailBL.newMailTextBuilder(MailTemplateId.ofRepoId(rfqTopic.getRfQ_InvitationWithoutQty_MailText_ID()));
		}
		else if (rfqReportType == RfQReportType.Won)
		{
			mailTextBuilder = mailBL.newMailTextBuilder(MailTemplateId.ofRepoId(rfqTopic.getRfQ_Win_MailText_ID()));
		}
		else if (rfqReportType == RfQReportType.Lost)
		{
			mailTextBuilder = mailBL.newMailTextBuilder(MailTemplateId.ofRepoId(rfqTopic.getRfQ_Lost_MailText_ID()));
		}
		else
		{
			throw new AdempiereException("@Invalid@ @Type@: " + rfqReportType);
		}

		mailTextBuilder.bpartner(rfqResponse.getC_BPartner());
		mailTextBuilder.bpartnerContact(rfqResponse.getAD_User());
		mailTextBuilder.record(rfqResponse);
		return mailTextBuilder;
	}
}
