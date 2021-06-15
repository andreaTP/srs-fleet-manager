package org.bf2.srs.fleetmanager.storage;

/**
 * Since we have unified create & update methods, this is not a `NotFound` or `AlreadyExists` type error,
 * since then the entity would be created or updated, respectively.
 * <p>
 * This exception is usually a result of a ConstraintViolationException, because of e.g. uniqueness constraints.
 * <p>
 * Since validation is handled by the container using Hibernate Validator, this exception should not be used for that purpose.
 *
 * @author Jakub Senko <jsenko@redhat.com>
 */
public class StorageConflictException extends StorageException {

    private static final long serialVersionUID = -6235365490109511256L;

    public StorageConflictException() {
        super();
    }

    public StorageConflictException(String message) {
        super(message);
    }

    public static StorageConflictException create(String entityName) {
        return new StorageConflictException("Could not create, update or delete " + entityName + ". " +
                "Make sure the data is valid and does not conflict with other stored entities. " +
                "Make sure the entity it is not in use before deleting.");
    }
}