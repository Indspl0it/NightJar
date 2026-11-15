package dev.nightjar.payload.ipc;

/**
 * The one way the payload reaches the app: {@code ContentResolver.call}. Production uses
 * {@link ContentResolverTransport}; tests use a fake.
 *
 * <p>Thread: called only from the IPC worker. Implementations must be synchronous and must translate
 * failures into {@link IpcException} with the right {@link IpcException.Kind}.
 */
public interface IpcTransport {

    /**
     * Performs one call.
     *
     * @return the reply; never {@code null} (a provider that returns {@code null} is a
     *     {@link IpcException.Kind#REJECTED} failure)
     * @throws IpcException when the call fails
     */
    IpcReply call(IpcRequest request) throws IpcException;
}
