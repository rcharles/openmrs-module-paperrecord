/*
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */

package org.openmrs.module.paperrecord;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.ListUtils;
import org.apache.commons.collections.Predicate;
import org.apache.commons.lang.StringUtils;
import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PatientIdentifierType;
import org.openmrs.Person;
import org.openmrs.api.APIException;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.emrapi.EmrApiProperties;
import org.openmrs.module.emrapi.utils.GeneralUtils;
import org.openmrs.module.idgen.service.IdentifierSourceService;
import org.openmrs.module.paperrecord.db.PaperRecordDAO;
import org.openmrs.module.paperrecord.db.PaperRecordMergeRequestDAO;
import org.openmrs.module.paperrecord.db.PaperRecordRequestDAO;
import org.openmrs.module.paperrecord.template.IdCardLabelTemplate;
import org.openmrs.module.paperrecord.template.LabelTemplate;
import org.openmrs.module.paperrecord.template.PaperFormLabelTemplate;
import org.openmrs.module.paperrecord.template.PaperRecordLabelTemplate;
import org.openmrs.module.printer.Printer;
import org.openmrs.module.printer.PrinterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.openmrs.module.paperrecord.PaperRecordRequest.PENDING_STATUSES;
import static org.openmrs.module.paperrecord.PaperRecordRequest.Status;

public class PaperRecordServiceImpl extends BaseOpenmrsService implements PaperRecordService {


    // TODO remove medical record location property from request
    // TODO db query should now use medical record location of underlying paper record
    // TODO db changeset to remove location\
    // TODO merge request location?

    // the methods to request and create a record use this method to make sure they have a lock on the patient before operating,
    // so we can avoid creating duplicate requests and/or creates
    private static Map<Integer, Object> patientLock = new HashMap<Integer, Object>();

    private final Logger log = LoggerFactory.getLogger(getClass());

    private PaperRecordDAO paperRecordDAO;

    private PaperRecordRequestDAO paperRecordRequestDAO;

    private PaperRecordMergeRequestDAO paperRecordMergeRequestDAO;

    private PatientService patientService;

    private IdentifierSourceService identifierSourceService;

    private PrinterService printerService;

    private EmrApiProperties emrApiProperties;

    private PaperRecordProperties paperRecordProperties;

    private PaperRecordLabelTemplate paperRecordLabelTemplate;

    private PaperFormLabelTemplate paperFormLabelTemplate;

    private IdCardLabelTemplate idCardLabelTemplate;

    public void setPaperRecordDAO(PaperRecordDAO paperRecordDAO) {
        this.paperRecordDAO = paperRecordDAO;
    }

    public void setPaperRecordRequestDAO(PaperRecordRequestDAO paperRecordRequestDAO) {
        this.paperRecordRequestDAO = paperRecordRequestDAO;
    }

    public void setPaperRecordMergeRequestDAO(PaperRecordMergeRequestDAO paperRecordMergeRequestDAO) {
        this.paperRecordMergeRequestDAO = paperRecordMergeRequestDAO;
    }

    public void setPatientService(PatientService patientService) {
        this.patientService = patientService;
    }

    public void setEmrApiProperties(EmrApiProperties emrApiProperties) {
        this.emrApiProperties = emrApiProperties;
    }

    public void setPaperRecordProperties(PaperRecordProperties paperRecordProperties) {
        this.paperRecordProperties = paperRecordProperties;
    }

    public void setPaperRecordLabelTemplate(PaperRecordLabelTemplate paperRecordLabelTemplate) {
        this.paperRecordLabelTemplate = paperRecordLabelTemplate;
    }

    public void setPaperFormLabelTemplate(PaperFormLabelTemplate paperFormLabelTemplate) {
        this.paperFormLabelTemplate = paperFormLabelTemplate;
    }

    public void setIdCardLabelTemplate(IdCardLabelTemplate idCardLabelTemplate) {
        this.idCardLabelTemplate = idCardLabelTemplate;
    }

    @Override
    public void setPrinterService(PrinterService printerService) {
        this.printerService = printerService;
    }

    @Override
    public void setIdentifierSourceService(IdentifierSourceService identifierSourceService) {
        this.identifierSourceService = identifierSourceService;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean paperRecordIdentifierInUse(String identifier, Location medicalRecordLocation) {
        List<PatientIdentifier> identifiers = patientService.getPatientIdentifiers(identifier,
                Collections.singletonList(paperRecordProperties.getPaperRecordIdentifierType()),
                Collections.singletonList(getMedicalRecordLocationAssociatedWith(medicalRecordLocation)), null, null);

        return identifiers != null && identifiers.size() > 0 ? true : false;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean paperRecordExistsWithIdentifier(String identifier, Location medicalRecordLocation) {
        return paperRecordDAO.findPaperRecord(identifier, getMedicalRecordLocationAssociatedWith(medicalRecordLocation)) != null;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean paperRecordExistsForPatientWithPrimaryIdentifier(String patientIdentifier, Location medicalRecordLocation) {

        List<Patient> patients = patientService.getPatients(null, patientIdentifier, Collections.singletonList(emrApiProperties.getPrimaryIdentifierType()), true);

        if (patients == null || patients.size() == 0) {
            return false;
        }

        if (patients.size() > 1) {
            // data model should prevent us from ever getting her, but just in case
            throw new APIException("Multiple patients found with identifier " + patientIdentifier);
        } else {
           return paperRecordExistsForPatient(patients.get(0), medicalRecordLocation);
        }

    }

    @Override
    @Transactional(readOnly = true)
    public boolean paperRecordExistsForPatient(Patient patient, Location medicalRecordLocation) {
        List<PaperRecord> paperRecords = paperRecordDAO.findPaperRecords(patient, getMedicalRecordLocationAssociatedWith(medicalRecordLocation));
        return paperRecords != null && paperRecords.size() > 0 ? true : false;
    }

    @Override
    @Transactional(readOnly = true)
    public PaperRecordRequest getPaperRecordRequestById(Integer id) {
        return paperRecordRequestDAO.getById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public PaperRecordMergeRequest getPaperRecordMergeRequestById(Integer id) {
        return paperRecordMergeRequestDAO.getById(id);
    }

    @Override
    public List<PaperRecordRequest> requestPaperRecord(Patient patient, Location medicalRecordLocation, Location requestLocation) {

        // TODO: we will have to handle the case if there is already a request for this patient's record in the "SENT" state
        // TODO: (ie, what to do if the record is already out on the floor--right now it will just create a new request)

        if (patient == null) {
            throw new IllegalArgumentException("Patient cannot be null");
        }

        if (medicalRecordLocation == null) {
            throw new IllegalArgumentException("Record Location cannot be null");
        }

        if (requestLocation == null) {
            throw new IllegalArgumentException("Request Location cannot be null");
        }

        // fetch the nearest medical record location (or just return the given location if it is a valid
        // medical record location)
        Location recordLocation = getMedicalRecordLocationAssociatedWith(medicalRecordLocation);

        List<PaperRecordRequest> requests;

        synchronized(lockOnPatient(patient)) {
            requests = Context.getService(PaperRecordService.class).requestPaperRecordInternal(patient, recordLocation,requestLocation);
        }

        return requests;
    }

    @Override
    @Transactional
    public List<PaperRecordRequest> requestPaperRecordInternal(Patient patient, Location recordLocation, Location requestLocation) {

        // TODO: we will have to handle the case if there is already a request for this patient's record in the "SENT" state
        // TODO: (ie, what to do if the record is already out on the floor--right now it will just create a new request)

        // fetch any pending request for this patient at this record location
        List<PaperRecordRequest> requests = paperRecordRequestDAO.findPaperRecordRequests(PENDING_STATUSES, patient,
                recordLocation, null);

        // if pending record request exists, simply update that request location and return it, and delet duplicates
        // TODO: support multiple requests for the same record from different locations at the same time, instead of this "LAST REQUEST WINS" scenario, and deleting duplicqtes
        if (requests.size() > 0) {

            Iterator<PaperRecordRequest> i = requests.iterator();
            PaperRecordRequest firstRequest = i.next();
            firstRequest.setRequestLocation(requestLocation);
            paperRecordRequestDAO.saveOrUpdate(firstRequest);

            while (i.hasNext()) {
                PaperRecordRequest request = i.next();
                request.updateStatus(Status.CANCELLED);
                paperRecordRequestDAO.saveOrUpdate(request);
            }

            return requests;
        }
        // if no pending record exists, create new requests
        else {

            requests = new ArrayList<PaperRecordRequest>();

            // get records to create requests for
            List<PaperRecord> paperRecords = getPaperRecords(patient, recordLocation);

            // if no record, create one
            if (paperRecords == null || paperRecords.size() == 0) {
                paperRecords.add(createPaperRecord(patient, recordLocation));
            }

            // now create requests for all paper records for patient at location
            for (PaperRecord paperRecord : paperRecords) {

                PaperRecordRequest request = new PaperRecordRequest();
                request.setPaperRecord(paperRecord);
                request.setCreator(Context.getAuthenticatedUser());
                request.setDateCreated(new Date());
                request.setRequestLocation(requestLocation);
                paperRecordRequestDAO.saveOrUpdate(request);

                requests.add(request);
            }

            return requests;
        }
    }

    @Override
    @Transactional
    public PaperRecordRequest savePaperRecordRequest(PaperRecordRequest paperRecordRequest) {
        if (paperRecordRequest != null) {
            return paperRecordRequestDAO.saveOrUpdate(paperRecordRequest);
        }
        return null;
    }

    @Override
    public List<PaperRecordRequest> getOpenPaperRecordRequests() {
        return paperRecordRequestDAO.findPaperRecordRequests(Collections.singletonList(PaperRecordRequest.Status.OPEN),
                null, null, null);
    }

    @Override
    public List<PaperRecordRequest> getOpenPaperRecordRequests(Location medicalRecordLocation) {
        return paperRecordRequestDAO.findPaperRecordRequests(Collections.singletonList(PaperRecordRequest.Status.OPEN),
                null, getMedicalRecordLocationAssociatedWith(medicalRecordLocation), null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaperRecordRequest> getOpenPaperRecordRequestsToPull() {
        return new ArrayList<PaperRecordRequest> (CollectionUtils.select(getOpenPaperRecordRequests(), new Predicate() {
            @Override
            public boolean evaluate(Object request) {
                return ! ((PaperRecordRequest) request).getPaperRecord().getStatus().equals(PaperRecord.Status.PENDING_CREATION);
            }
        }));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaperRecordRequest> getOpenPaperRecordRequestsToPull(Location medicalRecordLocation) {
        return new ArrayList<PaperRecordRequest> (CollectionUtils.select(getOpenPaperRecordRequests(medicalRecordLocation), new Predicate() {
            @Override
            public boolean evaluate(Object request) {
                return ! ((PaperRecordRequest) request).getPaperRecord().getStatus().equals(PaperRecord.Status.PENDING_CREATION);
            }
        }));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaperRecordRequest> getOpenPaperRecordRequestsToCreate() {
       return new ArrayList<PaperRecordRequest> (CollectionUtils.select(getOpenPaperRecordRequests(), new Predicate() {
            @Override
            public boolean evaluate(Object request) {
                return ((PaperRecordRequest) request).getPaperRecord().getStatus().equals(PaperRecord.Status.PENDING_CREATION);
            }
        }));

    }

    @Override
    @Transactional(readOnly = true)
    public List<PaperRecordRequest> getOpenPaperRecordRequestsToCreate(Location medicalRecordLocation) {
        return new ArrayList<PaperRecordRequest> (CollectionUtils.select(getOpenPaperRecordRequests(medicalRecordLocation), new Predicate() {
            @Override
            public boolean evaluate(Object request) {
                return ((PaperRecordRequest) request).getPaperRecord().getStatus().equals(PaperRecord.Status.PENDING_CREATION);
            }
        }));

    }

    // we break this out into an external public and internal private method because we want the transaction to
    // occur within the synchronized block

    @Override
    public synchronized Map<String, List<String>> assignRequests(List<PaperRecordRequest> requests, Person assignee, Location location) throws UnableToPrintLabelException {

        if (requests == null) {
            throw new IllegalArgumentException("Requests cannot be null");
        }

        if (assignee == null) {
            throw new IllegalArgumentException("Assignee cannot be null");
        }

        // this outer block allows us to synchronize outside of the @Transaction (I think?)

        // HACK: we need to reference the service here because an internal call won't pick up the @Transactional on the
        // internal method; we could potentially wire the bean into itself, but are unsure of that
        // see PaperRecordService.assignRequestsInternal(...  for more information
        return Context.getService(PaperRecordService.class).assignRequestsInternal(requests, assignee, location);
    }


    // HACK; note that this method must be public in order for Spring to pick up the @Transactional annotation;
    // see PaperRecordService.assignRequestsInternal(...  for more information
    @Transactional(rollbackFor = UnableToPrintLabelException.class)
    public Map<String, List<String>> assignRequestsInternal(List<PaperRecordRequest> requests, Person assignee, Location location) throws UnableToPrintLabelException {

        Map<String, List<String>> response = new HashMap<String, List<String>>();
        response.put("success", new LinkedList<String>());
        response.put("error", new LinkedList<String>());

        for (PaperRecordRequest request : requests) {

            // as a sanity check, ignore any requests that aren't open
            if (request.getStatus() == Status.OPEN) {

                // we chose a different printing scheme based on whether or not a paper record needs to be created
                if (request.getPaperRecord().getStatus().equals(PaperRecord.Status.PENDING_CREATION)) {
                    printPaperRecordLabelSet(request, location);
                } else {
                    printPaperFormLabels(request, location, PaperRecordConstants.NUMBER_OF_FORM_LABELS_TO_PRINT);
                }

                request.updateStatus(Status.ASSIGNED);
                request.setAssignee(assignee);
                paperRecordRequestDAO.saveOrUpdate(request);

                response.get("success").add(request.getPaperRecord().getPatientIdentifier().getIdentifier());
            }
        }

        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaperRecordRequest> getAssignedPaperRecordRequests() {
        return paperRecordRequestDAO.findPaperRecordRequests(
                Collections.singletonList(PaperRecordRequest.Status.ASSIGNED), null, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaperRecordRequest> getAssignedPaperRecordRequests(Location medicalRecordLocation) {
        return paperRecordRequestDAO.findPaperRecordRequests(
                Collections.singletonList(PaperRecordRequest.Status.ASSIGNED), null, getMedicalRecordLocationAssociatedWith(medicalRecordLocation), null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaperRecordRequest> getAssignedPaperRecordRequestsToPull() {
        return new ArrayList<PaperRecordRequest> (CollectionUtils.select(getAssignedPaperRecordRequests(), new Predicate() {
            @Override
            public boolean evaluate(Object request) {
                return ! ((PaperRecordRequest) request).getPaperRecord().getStatus().equals(PaperRecord.Status.PENDING_CREATION);
            }
        }));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaperRecordRequest> getAssignedPaperRecordRequestsToPull(Location medicalRecordLocation) {
        return new ArrayList<PaperRecordRequest> (CollectionUtils.select(getAssignedPaperRecordRequests(medicalRecordLocation), new Predicate() {
            @Override
            public boolean evaluate(Object request) {
                return ! ((PaperRecordRequest) request).getPaperRecord().getStatus().equals(PaperRecord.Status.PENDING_CREATION);
            }
        }));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaperRecordRequest> getAssignedPaperRecordRequestsToCreate(Location medicalRecordLocation) {
        return new ArrayList<PaperRecordRequest> (CollectionUtils.select(getAssignedPaperRecordRequests(medicalRecordLocation), new Predicate() {
            @Override
            public boolean evaluate(Object request) {
                return ((PaperRecordRequest) request).getPaperRecord().getStatus().equals(PaperRecord.Status.PENDING_CREATION);
            }
        }));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaperRecordRequest> getAssignedPaperRecordRequestsToCreate() {
    return new ArrayList<PaperRecordRequest> (CollectionUtils.select(getAssignedPaperRecordRequests(), new Predicate() {
            @Override
            public boolean evaluate(Object request) {
                return ((PaperRecordRequest) request).getPaperRecord().getStatus().equals(PaperRecord.Status.PENDING_CREATION);
            }
        }));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaperRecordRequest> getPaperRecordRequestsByPatient(Patient patient) {
        return paperRecordRequestDAO.findPaperRecordRequests(null, patient, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public PaperRecordRequest getPendingPaperRecordRequestByIdentifier(String identifier, Location medicalRecordLocation) {
        List<PaperRecordRequest> requests = getPaperRecordRequestByIdentifierAndStatus(identifier, PENDING_STATUSES, medicalRecordLocation);

        if (requests == null || requests.size() == 0) {
            return null;
        } else if (requests.size() > 1) {
            throw new IllegalStateException("Duplicate record requests in the pending state with identifier " + identifier);
        } else {
            return requests.get(0);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PaperRecordRequest getAssignedPaperRecordRequestByIdentifier(String identifier, Location medicalRecordLocation) {
        List<PaperRecordRequest> requests = getPaperRecordRequestByIdentifierAndStatus(identifier, Collections.singletonList(Status.ASSIGNED), medicalRecordLocation);

        if (requests == null || requests.size() == 0) {
            return null;
        } else if (requests.size() > 1) {
            throw new IllegalStateException("Duplicate record requests in the assigned state with identifier " + identifier);
        } else {
            return requests.get(0);
        }

    }

    @Override
    @Transactional(readOnly = true)
    public List<PaperRecordRequest> getSentPaperRecordRequestByIdentifier(String identifier, Location medicalRecordLocation) {
        return getPaperRecordRequestByIdentifierAndStatus(identifier, Collections.singletonList(Status.SENT), medicalRecordLocation);
    }

    private List<PaperRecordRequest> getPaperRecordRequestByIdentifierAndStatus(String identifier, List<Status> statusList, Location medicalRecordLocation) {
        // first see if we find any requests by paper record identifier
        List<PaperRecordRequest> requests = getPaperRecordRequestByPaperRecordIdentifierAndStatus(identifier, statusList, medicalRecordLocation);

        // if no requests, see if this is another type of patient identifier (note tha this appears to be computationally expensive)
        if ((requests == null || requests.size() == 0)) {
            List<Patient> patients = patientService.getPatients(null, identifier, Collections.singletonList(emrApiProperties.getPrimaryIdentifierType()), true);
            if (patients != null && patients.size() > 0) {
                if (patients.size() > 1) {
                    throw new IllegalStateException("Duplicate patients exist with identifier " + identifier);
                } else {
                    requests = paperRecordRequestDAO.findPaperRecordRequests(statusList, patients.get(0), getMedicalRecordLocationAssociatedWith(medicalRecordLocation),
                            null);
                }
            }
        }
        return requests;
    }

    private List<PaperRecordRequest> getPaperRecordRequestByPaperRecordIdentifierAndStatus(String identifier, List<Status> statusList, Location medicalRecordLocation) {
        if (StringUtils.isBlank(identifier)) {
            return new ArrayList<PaperRecordRequest>();
        }
        return paperRecordRequestDAO.findPaperRecordRequests(statusList, null, getMedicalRecordLocationAssociatedWith(medicalRecordLocation), identifier);
    }

    @Override
    @Transactional(readOnly = true)
    public PaperRecordRequest getMostRecentSentPaperRecordRequest(PaperRecord paperRecord) {

        List<PaperRecordRequest> requests = paperRecordRequestDAO.findPaperRecordRequests(Collections.singletonList(Status.SENT),
                paperRecord);

        if (requests == null || requests.size() == 0) {
            return null;
        } else {
            Collections.sort(requests, new Comparator<PaperRecordRequest>() {
                @Override
                public int compare(PaperRecordRequest request1, PaperRecordRequest request2) {
                    // get date status changed should never be null, but just to be safe
                    return request1.getDateStatusChanged() == null ? 1 : request2.getDateStatusChanged() == null ? -1
                            : request1.getDateStatusChanged().compareTo(request2.getDateStatusChanged());
                }
            });
            return requests.get(requests.size() - 1);  // most recent is last one in list
        }
    }

    @Override
    @Transactional
    public void markPaperRecordRequestAsSent(PaperRecordRequest request) {

        // TODO: think more about a patient having multiple charts with the same dossier number?
        // TODO: think about the multiple records per location issue?
        request.updateStatus(Status.SENT);

        // TODO: **for now , this is where we note when/where a record has been created, at the time of sending** (does this make sense?)
        if (request.getPaperRecord().getStatus().equals(PaperRecord.Status.PENDING_CREATION)) {
            request.getPaperRecord().updateStatus(PaperRecord.Status.ACTIVE);
        }

        savePaperRecordRequest(request);
    }

    @Override
    @Transactional
    public void markPaperRecordRequestAsCancelled(PaperRecordRequest request) {
        request.updateStatus(Status.CANCELLED);
        savePaperRecordRequest(request);
    }

    @Override
    @Transactional
    public void markPaperRecordRequestAsReturned(PaperRecordRequest request) {
        request.updateStatus(Status.RETURNED);
        savePaperRecordRequest(request);
    }

    @Override
    @Transactional(readOnly = true)
    public void printPaperRecordLabel(PaperRecordRequest request, Location location) throws UnableToPrintLabelException {
        printPaperRecordLabels(request, location, 1);
    }


    @Override
    @Transactional(readOnly = true)
    public void printPaperRecordLabels(PaperRecordRequest request, Location location, Integer count) throws UnableToPrintLabelException {
        printLabels(request.getPaperRecord().getPatientIdentifier().getPatient(), request.getPaperRecord().getPatientIdentifier().getIdentifier(), location, count, paperRecordLabelTemplate);
    }

    @Override
    @Transactional(readOnly = true)
    public void printPaperRecordLabels(Patient patient, Location location, Integer count) throws UnableToPrintLabelException {

        // generally, in our current design, a patient should only have one paper record per location
        List<PaperRecord> paperRecords = getPaperRecords(patient, location);

        if (paperRecords != null && paperRecords.size() > 0) {
            for (PaperRecord paperRecord : paperRecords) {
                    printLabels(patient, paperRecord.getPatientIdentifier().getIdentifier(), location, count, paperRecordLabelTemplate);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void printPaperFormLabels(PaperRecordRequest request, Location location, Integer count) throws UnableToPrintLabelException {
        printLabels(request.getPaperRecord().getPatientIdentifier().getPatient(), request.getPaperRecord().getPatientIdentifier().getIdentifier(), location, count, paperFormLabelTemplate);

    }

    @Override
    @Transactional(readOnly = true)
    public void printPaperFormLabels(Patient patient, Location location, Integer count) throws UnableToPrintLabelException {

        // generally, in our current design, a patient should only have one paper record per location
        List<PaperRecord> paperRecords = getPaperRecords(patient, location);

        if (paperRecords != null && paperRecords.size() > 0) {
            for (PaperRecord paperRecord : paperRecords) {
                printLabels(patient, paperRecord.getPatientIdentifier().getIdentifier(), location, count, paperFormLabelTemplate);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void printIdCardLabel(Patient patient, Location location) throws UnableToPrintLabelException {
        printLabels(patient, null, location, 1, idCardLabelTemplate);
    }

    @Override
    public void printPaperRecordLabelSet(PaperRecordRequest request, Location location) throws UnableToPrintLabelException{
        printPaperRecordLabel(request, location);
        printPaperFormLabels(request, location, PaperRecordConstants.NUMBER_OF_FORM_LABELS_TO_PRINT);
        printIdCardLabel(request.getPaperRecord().getPatientIdentifier().getPatient(), location);
    }

    private void printLabels(Patient patient, String identifier, Location location, Integer count, LabelTemplate template) throws UnableToPrintLabelException {
        if (count == null || count == 0) {
            return;  // just do nothing if we don't have a count
        }

        String data = template.generateLabel(patient, identifier);
        String encoding = template.getEncoding();

        // just duplicate the data if we are printing multiple labels
        StringBuffer dataBuffer = new StringBuffer();
        dataBuffer.append(data);

        int countDown = count;

        while (countDown > 1) {
            dataBuffer.append(data);
            countDown--;
        }

        try {
            printerService.printViaSocket(dataBuffer.toString(), Printer.Type.LABEL, location, encoding, false, 500 + (count * 100));   // add a slight delay to avoid overloading a single printer
        } catch (Exception e) {
            throw new UnableToPrintLabelException("Unable to print paper record label at location " + location + " for patient " + patient, e);
        }
    }

    @Override
    @Transactional
    public void markPaperRecordsForMerge(PaperRecord preferredPaperRecord, PaperRecord notPreferredPaperRecord) {

        if (!preferredPaperRecord.getRecordLocation().equals(notPreferredPaperRecord.getRecordLocation())) {
            throw new IllegalArgumentException("Cannot merge two records from different locations: "
                    + preferredPaperRecord + ", " + notPreferredPaperRecord);
        }

        List<PaperRecordRequest> pendingRequests = ListUtils.union(paperRecordRequestDAO.findPaperRecordRequests(PENDING_STATUSES, preferredPaperRecord),
                paperRecordRequestDAO.findPaperRecordRequests(PENDING_STATUSES, notPreferredPaperRecord));

        // for now, we will just cancel any pending paper record requests for the preferred patient and non-preferred patient
        for (PaperRecordRequest request : pendingRequests) {
            markPaperRecordRequestAsCancelled(request);
        }

        // also copy over all the non-preferred patient SENT requests to the new patient
        // (this probably isn't exactly right, but it should prevent an error from being thrown one of these charts is returned to the archives room)
        for (PaperRecordRequest request : paperRecordRequestDAO.findPaperRecordRequests(Collections.singletonList(Status.SENT), notPreferredPaperRecord)) {
            request.setPaperRecord(preferredPaperRecord);
            savePaperRecordRequest(request);
        }

        // create the request
        PaperRecordMergeRequest mergeRequest = new PaperRecordMergeRequest();
        mergeRequest.setStatus(PaperRecordMergeRequest.Status.OPEN);
        mergeRequest.setPreferredPaperRecord(preferredPaperRecord);
        mergeRequest.setNotPreferredPaperRecord(notPreferredPaperRecord);
        mergeRequest.setCreator(Context.getAuthenticatedUser());
        mergeRequest.setDateCreated(new Date());

        paperRecordMergeRequestDAO.saveOrUpdate(mergeRequest);

        // void the non-preferred identifier; we do this now (instead of when the merge is confirmed)
        // so that all new requests for records for this patient use the right identifier
        patientService.voidPatientIdentifier(notPreferredPaperRecord.getPatientIdentifier(), "voided during paper record merge");
    }

    @Override
    @Transactional
    public void markPaperRecordsAsMerged(PaperRecordMergeRequest mergeRequest) {
        // then just mark the request as merged
        mergeRequest.setStatus(PaperRecordMergeRequest.Status.MERGED);
        paperRecordMergeRequestDAO.saveOrUpdate(mergeRequest);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaperRecordMergeRequest> getOpenPaperRecordMergeRequests(Location medicalRecordlocation) {
        return paperRecordMergeRequestDAO.findPaperRecordMergeRequest(
                Collections.singletonList(PaperRecordMergeRequest.Status.OPEN),
                getMedicalRecordLocationAssociatedWith(medicalRecordlocation));
    }

    @Override
    @Transactional
    public void expirePendingPullRequests(Date expireDate) {

        List<PaperRecordRequest> pullRequests = new ArrayList<PaperRecordRequest>();

        // note that since we are calling the other service methods directly, they
        // won't be transactional, so we need to make sure this method is transactional

        pullRequests.addAll(getOpenPaperRecordRequestsToPull());
        pullRequests.addAll(getAssignedPaperRecordRequestsToPull());

        for (PaperRecordRequest request : pullRequests) {
            if (request.getDateCreated().before(expireDate)) {
                markPaperRecordRequestAsCancelled(request);
            }
        }
    }

    @Override
    @Transactional
    public void expirePendingCreateRequests(Date expireDate) {

        List<PaperRecordRequest> createRequests = new ArrayList<PaperRecordRequest>();

        // note that since we are calling the other service methods directly, they
        // won't be transactional, so we need to make sure this method is transactional

        createRequests.addAll(getOpenPaperRecordRequestsToCreate());
        createRequests.addAll(getAssignedPaperRecordRequestsToCreate());

        for (PaperRecordRequest request : createRequests) {
            if (request.getDateCreated().before(expireDate)) {
                markPaperRecordRequestAsCancelled(request);
            }
        }

    }

    @Override
    public PaperRecord createPaperRecord(Patient patient, Location location) {

        if (patient == null) {
            throw new IllegalArgumentException("Patient shouldn't be null");
        }

        PaperRecord paperRecord;

        Location medicalRecordLocation = getMedicalRecordLocationAssociatedWith(location);

        synchronized(lockOnPatient(patient)) {
            paperRecord = Context.getService(PaperRecordService.class).createPaperRecordInternal(patient, medicalRecordLocation);
        }

        return paperRecord;
    }

    @Override
    @Transactional
    public PaperRecord createPaperRecordInternal(Patient patient, Location medicalRecordLocation) {
        if (patient == null) {
            throw new IllegalArgumentException("Patient shouldn't be null");
        }

        PatientIdentifier paperRecordIdentifier  = getPaperRecordIdentifier(patient, medicalRecordLocation);

        // create paper record identifier if necessary
        if (paperRecordIdentifier == null) {
            PatientIdentifierType paperRecordIdentifierType = paperRecordProperties.getPaperRecordIdentifierType();

            String paperRecordId = "";

            paperRecordId = identifierSourceService.generateIdentifier(paperRecordIdentifierType, medicalRecordLocation,
                    "generating a new paper record identifier number");

            if (paperRecordId == null) {
                throw new APIException("Unable to generate paper record identifier for patient " + patient);
            }

            // double check to make sure this identifier is not in use
            while (paperRecordIdentifierInUse(paperRecordId, medicalRecordLocation)) {
                log.error("Attempted to generate duplicate paper record identifier " + paperRecordId );
                paperRecordId = identifierSourceService.generateIdentifier(paperRecordIdentifierType, medicalRecordLocation,
                        "generating a new paper record identifier number");
            }

            paperRecordIdentifier = new PatientIdentifier(paperRecordId, paperRecordIdentifierType,
                    medicalRecordLocation);
            patient.addIdentifier(paperRecordIdentifier);
            patientService.savePatientIdentifier(paperRecordIdentifier);

        }

        PaperRecord paperRecord = getPaperRecord(paperRecordIdentifier, medicalRecordLocation);
        if (paperRecord != null) {
            log.warn("createPaperRecord called for patient " + paperRecordIdentifier + " who already has record at " + medicalRecordLocation);
        }
        else {
            paperRecord = new PaperRecord();
            paperRecord.updateStatus(PaperRecord.Status.PENDING_CREATION);
            paperRecord.setPatientIdentifier(paperRecordIdentifier);
            paperRecord.setRecordLocation(medicalRecordLocation);
            savePaperRecord(paperRecord);   // TODO: proxy issues here?
        }

        return paperRecord;
    }



    @Override
    @Transactional
    public List<PaperRecord> getPaperRecords(Patient patient) {
        return paperRecordDAO.findPaperRecords(patient, null);
    }

    @Override
    @Transactional
    public List<PaperRecord> getPaperRecords(Patient patient, Location paperRecordLocation) {
        if (paperRecordLocation != null) {
            paperRecordLocation = getMedicalRecordLocationAssociatedWith(paperRecordLocation);
        }
        return paperRecordDAO.findPaperRecords(patient, paperRecordLocation);
    }

    @Override
    public PaperRecord getPaperRecord(PatientIdentifier patientIdentifier, Location paperRecordLocation) {
        if (paperRecordLocation != null) {
            paperRecordLocation = getMedicalRecordLocationAssociatedWith(paperRecordLocation);
        }
        return paperRecordDAO.findPaperRecord(patientIdentifier, paperRecordLocation);
    }

    @Override
    public PaperRecord savePaperRecord(PaperRecord paperRecord) {
        return paperRecordDAO.saveOrUpdate(paperRecord);
    }

    @Override
    public Location getMedicalRecordLocationAssociatedWith(Location location) {

        if (location != null) {
            if (location.hasTag(paperRecordProperties.getMedicalRecordLocationLocationTag().toString())) {
                return location;
            } else {
                return getMedicalRecordLocationAssociatedWith(location.getParentLocation());
            }
        }

        throw new IllegalStateException(
                "There is no matching location with the tag: " + paperRecordProperties.getMedicalRecordLocationLocationTag().toString());
    }

    @Override
    public Location getArchivesLocationAssociatedWith(Location location) {

        Location l = getArchivesLocationHelper(getMedicalRecordLocationAssociatedWith(location));

        if (l == null) {
            throw new IllegalStateException("No archives room location found for location " + location);
        }

        return l;
    }

    private Location getArchivesLocationHelper(Location location) {

        if (location.hasTag(paperRecordProperties.getArchivesLocationTag().toString())) {
            return location;
        }

        if (location.getChildLocations(false) != null) {
            for (Location l : location.getChildLocations(false)) {
                Location match = getArchivesLocationHelper(l);
                if (match != null) {
                    return match;
                }
            }
        }

        return null;
    }

    private Object lockOnPatient(Patient patient) {

        if (!patientLock.containsKey(patient.getId())) {
            patientLock.put(patient.getId(), new Object());
        }

        return patientLock.get(patient.getId());
    }

    private PatientIdentifier getPaperRecordIdentifier(Patient patient, Location medicalRecordLocation) {
        PatientIdentifier paperRecordIdentifier = GeneralUtils.getPatientIdentifier(patient,
                paperRecordProperties.getPaperRecordIdentifierType(), medicalRecordLocation);
        return paperRecordIdentifier;
    }


    // TODO: old, more complex merge functionality that we are ignoring--probably can be deleted
/*    private void mergePendingPaperRecordRequests(PaperRecordMergeRequest mergeRequest) {

        // (note that we are not searching by patient here because the patient may have been changed during the merge)
        List<PaperRecordRequest> preferredRequests = paperRecordRequestDAO.findPaperRecordRequests(PENDING_STATUSES,
                mergeRequest.getPreferredPaperRecord());

        if (preferredRequests.size() > 1) {
            throw new IllegalStateException(
                    "Duplicate pending record requests exist with identifier " + mergeRequest.getPreferredPaperRecord().getPatientIdentifier());
        }

        List<PaperRecordRequest> notPreferredRequests = paperRecordRequestDAO.findPaperRecordRequests(PENDING_STATUSES,
                mergeRequest.getNotPreferredPaperRecord()));

        if (notPreferredRequests.size() > 1) {
            throw new IllegalStateException(
                    "Duplicate pending record requests exist with identifier " + mergeRequest.getNotPreferredIdentifier());
        }

        PaperRecordRequest preferredRequest = null;
        PaperRecordRequest notPreferredRequest = null;

        if (preferredRequests.size() == 1) {
            preferredRequest = preferredRequests.get(0);
        }

        if (notPreferredRequests.size() == 1) {
            notPreferredRequest = notPreferredRequests.get(0);
        }

        // if both the preferred and not-preferred records have a request, we need to
        // cancel on of them
        if (preferredRequest != null && notPreferredRequest != null) {
            // update the request location if the non-preferred  is more recent
            if (notPreferredRequest.getDateCreated().after(preferredRequest.getDateCreated())) {
                preferredRequest.setRequestLocation(notPreferredRequest.getRequestLocation());
            }

            notPreferredRequest.updateStatus(Status.CANCELLED);
            paperRecordRequestDAO.saveOrUpdate(preferredRequest);
            paperRecordRequestDAO.saveOrUpdate(notPreferredRequest);
        }

        // if there is only a non-preferred request, we need to update it with the right identifier
        if (preferredRequest == null && notPreferredRequest != null) {

           // notPreferredRequest.setIdentifier(mergeRequest.getPreferredIdentifier());
            paperRecordRequestDAO.saveOrUpdate(notPreferredRequest);
        }

    }*/

  /*  private void closeOutSentPaperRecordRequestsForNotPreferredRecord(PaperRecordMergeRequest mergeRequest) {
        List<PaperRecordRequest> notPreferredRequests = paperRecordRequestDAO.findPaperRecordRequests(
                Collections.singletonList(Status.SENT), mergeRequest.getNotPreferredPaperRecord());

        for (PaperRecordRequest notPreferredRequest : notPreferredRequests) {
            notPreferredRequest.updateStatus(Status.RETURNED);
            paperRecordRequestDAO.saveOrUpdate(notPreferredRequest);
        }
    }*/

}
