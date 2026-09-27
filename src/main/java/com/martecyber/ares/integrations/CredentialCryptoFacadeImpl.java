package com.martecyber.ares.integrations;

import org.springframework.stereotype.Component;

/** Thin adapter exposing {@link CredentialEncryptionService} to plugins as the {@code
 *  ares-sdk}-owned {@link CredentialCryptoFacade}. */
@Component
class CredentialCryptoFacadeImpl implements CredentialCryptoFacade {

    private final CredentialEncryptionService encryption;

    CredentialCryptoFacadeImpl(CredentialEncryptionService encryption) {
        this.encryption = encryption;
    }

    @Override
    public EncryptedValue encrypt(String plaintext) {
        CredentialEncryptionService.Encrypted e = encryption.encrypt(plaintext);
        return new EncryptedValue(e.ciphertext(), e.iv());
    }

    @Override
    public String decrypt(byte[] ciphertext, byte[] iv) {
        return encryption.decrypt(ciphertext, iv);
    }
}
