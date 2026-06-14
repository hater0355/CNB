package chatapp;

interface MessageCryptoService {
    default String encryptForPrivateChat(String plainText, String conversationKey) {
        return plainText;
    }

    default String decryptForPrivateChat(String cipherText, String conversationKey) {
        return cipherText;
    }

    default boolean enabled() {
        return false;
    }
}
