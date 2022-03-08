(ns puppetlabs.ssl-utils.testutils
  (:import (java.io ByteArrayOutputStream ByteArrayInputStream)
           (java.security.cert X509Certificate)
           (java.security MessageDigest PublicKey)
           (javax.security.auth.x500 X500Principal)
           (org.bouncycastle.asn1.x500 X500Name)
           (org.bouncycastle.pkcs PKCS10CertificationRequest)
           (org.joda.time DateTime Period)
           (org.bouncycastle.asn1.x509 SubjectPublicKeyInfo)
           (com.puppetlabs.ssl_utils SSLUtils))
  (:require [clojure.test :refer :all]
            [clojure.java.io :refer [resource reader]]
            [me.raynes.fs :as fs]
            [puppetlabs.ssl-utils.core :refer :all]))

(defn pubkey-sha1
  "Gets the SHA-1 digest of the raw bytes of the provided publickey."
  [pub-key]
  {:pre [(public-key? pub-key)]
   :post [(vector? %)
          (every? integer? %)]}
  (let [bytes   (-> ^PublicKey
                    pub-key
                    .getEncoded
                    SubjectPublicKeyInfo/getInstance
                    .getPublicKeyData
                    .getBytes)]
    (vec (.digest (MessageDigest/getInstance "SHA1") bytes))))

(defn open-ssl-file
  [filepath]
  (resource (str "puppetlabs/ssl_utils/examples/ssl/" filepath)))

(defn write-to-pem-stream
  ([object] (write-to-pem-stream object obj->pem!))
  ([object write-function]
   (let [pem-stream (ByteArrayOutputStream.)]
     (write-function object pem-stream)
     (-> pem-stream
         (.toByteArray)
         (ByteArrayInputStream.)))))

(defmulti has-subject?
          "Returns true if x has the subject identified by the x500-name string or `X500Name`.
          Default implementations are provided for `X509Certificate` and `PKCS10CertificationRequest`."
          (fn [x x500-name]
            [(class x) (class x500-name)]))

(defmethod has-subject? [X509Certificate String]
  [cert x500-name]
  (= x500-name (-> ^X509Certificate cert .getSubjectX500Principal .getName)))

(defmethod has-subject? [X509Certificate X500Name]
  [cert x500-name]
  (has-subject? cert (str x500-name)))

(defmethod has-subject? [PKCS10CertificationRequest String]
  [csr x500-name]
  (= x500-name (-> csr .getSubject str)))

(defmethod has-subject? [PKCS10CertificationRequest X500Name]
  [csr x500-name]
  (= x500-name (.getSubject csr)))

(defmulti issued-by?
          "Returns true if x was issued by the x500-name string or `X500Name`.
          Default implementations are provided for `X509Certificate` and `X509CRL`."
          (fn [_ x500-name]
            (class x500-name)))

(defmethod issued-by? String
  [x x500-name]
  (= x500-name (-> x .getIssuerX500Principal .getName)))

(defmethod issued-by? X500Name
  [x x500-name]
  (issued-by? x (str x500-name)))

(defn generate-past-date []
  (-> (DateTime/now)
      (.minus (Period/days 1))
      (.toDate)))

(defn generate-not-before-date []
  (generate-past-date))

(defn generate-future-date []
  (-> (DateTime/now)
      (.plus (Period/years 5))
      (.toDate)))

(defn generate-not-after-date []
  (generate-future-date))

(defn str->bytes
  "Turn a `String s` into `bytes[]`"
  [^String s]
  (bytes (byte-array (map (comp byte int) s))))

(defn generate-expired-crl
  [issuer issuer-private-key issuer-public-key]
  (SSLUtils/generateCRL issuer issuer-private-key issuer-public-key
                        (.toDate (DateTime/now))
                        (generate-past-date)
                        BigInteger/ZERO
                        nil))

(defn generate-not-yet-valid-crl
  [issuer issuer-private-key issuer-public-key]
  (SSLUtils/generateCRL issuer issuer-private-key issuer-public-key
                        (generate-future-date)
                        (generate-future-date)
                        BigInteger/ZERO
                        nil))

(defn generate-newer-crl
  [issuer issuer-private-key issuer-public-key]
  (SSLUtils/generateCRL issuer issuer-private-key issuer-public-key
                        (.toDate (DateTime/now))
                        (generate-future-date)
                        BigInteger/ONE
                        nil))

(defn generate-newest-crl
  [issuer issuer-private-key issuer-public-key]
  (SSLUtils/generateCRL issuer issuer-private-key issuer-public-key
                        (.toDate (DateTime/now))
                        (generate-future-date)
                        BigInteger/TEN
                        nil))

;; This does not contain delta CRL content, just the delta CRL indicator,
;; so should only be used for testing purposes.
(defn generate-delta-crl
  [issuer issuer-private-key issuer-public-key]
  (SSLUtils/generateCRL issuer issuer-private-key issuer-public-key
                        (.toDate (DateTime/now))
                        (generate-future-date)
                        BigInteger/ONE
                        [(javaize (delta-crl-indicator BigInteger/ZERO))]))

(defn create-truncated-subject-key-identifier
  [key]
  {:oid subject-key-identifier-oid
   :critical false
   :value key
   :options {:truncate true}})

(defn create-truncated-authority-key-identifier
  [key]
  {:oid      authority-key-identifier-oid
   :critical false
   :value    {:public-key    key
              :serial-number nil
              :issuer-dn     nil
              :cert          nil}
   :options {:truncate true}})

(defn swap-extension
  [extensions extension]
  (-> (remove #(= (:oid extension) (:oid %)) extensions)
      (conj extension)))

(defn generate-crl-with-bad-signature
  [issuer _ _]
  (let [random-keys (generate-key-pair 2048)
        public-key (get-public-key random-keys)
        private-key (get-private-key random-keys)]
    (SSLUtils/generateCRL issuer private-key public-key)))

(defn generate-multiple-crls
  [issuer issuer-private-key issuer-public-key]
  {:original (generate-crl issuer issuer-private-key issuer-public-key)
   :new (generate-newer-crl issuer issuer-private-key issuer-public-key)
   :newest (generate-newest-crl issuer issuer-private-key issuer-public-key)})

(defn generate-ca-cert
  [issuer-name issuer-key-pair serial root?]
  (let [subject-name (if root?
                       issuer-name
                       (cn (format "Intermediate CA %d" serial)))
        key-pair (if root?
                  issuer-key-pair
                  (generate-key-pair 2048))
        public-key (get-public-key key-pair)
        not-before (generate-not-before-date)
        not-after (generate-not-after-date)
        cert (sign-certificate issuer-name (get-private-key issuer-key-pair)
                               serial not-before not-after subject-name public-key
                               (create-ca-extensions (get-public-key issuer-key-pair) public-key))]
    [cert key-pair]))

(defn generate-cert-chain-with-crls
  ([number-of-certs]
   (generate-cert-chain-with-crls number-of-certs
                                  generate-crl
                                  (generate-key-pair 2048)))
  ([number-of-certs generate-crl-fn]
   (generate-cert-chain-with-crls number-of-certs
                                  generate-crl-fn
                                  (generate-key-pair 2048)))
  ([number-of-certs generate-crl-fn root-key-pair]
   (let [root-public-key (get-public-key root-key-pair)
         root-private-key (get-private-key root-key-pair)
         root-name (cn "Root CA")
         root-cert (first (generate-ca-cert root-name root-key-pair 666 true))
         root-crl (generate-crl-fn (X500Principal. root-name)
                                   root-private-key root-public-key)]
     (loop [certs [root-cert]
            crls [root-crl]
            certs-to-generate (dec number-of-certs)
            issuer root-cert
            issuer-key-pair root-key-pair]
       (if (< certs-to-generate 1)
         [certs crls issuer-key-pair]
         (let [[new-cert new-key-pair] (generate-ca-cert
                                        (cn (get-cn-from-x509-certificate issuer))
                                        issuer-key-pair
                                        certs-to-generate
                                        false)
               new-private-key (get-private-key new-key-pair)
               new-public-key (get-public-key new-key-pair)
               new-crl (generate-crl-fn (.getSubjectX500Principal ^X509Certificate new-cert)
                                        new-private-key new-public-key)]
           (recur (cons new-cert certs)
                  (cons new-crl crls)
                  (dec certs-to-generate)
                  new-cert
                  new-key-pair)))))))

(defn generate-cert-chain-with-revoked-cert
  [number-of-certs]
  (if (< number-of-certs 2)
    (throw (Exception. (format "Can't perform revocations on a %d-cert chain."
                               number-of-certs))))
  (let [root-key-pair (generate-key-pair 2048)
        [certs crls leaf-keys] (generate-cert-chain-with-crls number-of-certs
                                                              generate-crl
                                                              root-key-pair)
        cert-to-revoke (nth certs (- number-of-certs 2))
        crl-to-update (last crls)
        updated-crl (revoke crl-to-update
                            (get-private-key root-key-pair)
                            (get-public-key root-key-pair)
                            (get-serial cert-to-revoke))]
    [certs (-> crls drop-last (conj updated-crl) leaf-keys)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Writing test files

;; The following helpers can be used to create test fixtures via the repl.
;; To start the repl, run `lein repl`.
;; Within the repl, run the following (with your desired function and arguments):
;; ```
;; (require '[puppetlabs.ssl-utils.testutils :as t])
;; (t/write-valid-cert-chain-and-crls 3 "your-subpath")
;; ```

(def test-files-path "test-resources/puppetlabs/ssl_utils/examples/ssl")

(defn write-certs-and-crls
  [certs crls test-subpath certs-filename crls-filename]
  (let [dir-path (fs/file test-files-path test-subpath)
        certs-path (fs/file dir-path certs-filename)
        crls-path (fs/file dir-path crls-filename)]
    (fs/mkdirs dir-path)
    (objs->pem! certs certs-path)
    (objs->pem! crls crls-path)))

(defn write-valid-cert-chain-and-crls
  [number-of-certs test-subpath]
  (let [certs-filename (str number-of-certs "-cert-chain.pem")
        crls-filename (str number-of-certs "-crl-chain.pem")
        [certs crls _] (generate-cert-chain-with-crls number-of-certs)]
    (write-certs-and-crls certs crls test-subpath certs-filename crls-filename)))

(defn write-expired-crl
  [test-subpath]
  (let [cert-filename "cert-with-expired-crl.pem"
        crl-filename "expired-crl.pem"
        [cert expired-crl _] (generate-cert-chain-with-crls 1 generate-expired-crl)]
    (write-certs-and-crls cert expired-crl test-subpath cert-filename crl-filename)))

(defn write-not-yet-valid-crl
  [test-subpath]
  (let [cert-filename "cert-with-not-valid-crl.pem"
        crl-filename "not-yet-valid-crl.pem"
        [cert crl _] (generate-cert-chain-with-crls 1 generate-not-yet-valid-crl)]
    (write-certs-and-crls cert crl test-subpath cert-filename crl-filename)))

(defn write-crl-with-bad-sig
  [test-subpath]
  (let [cert-filename "cert-with-crl-bad-sig.pem"
        crl-filename "crl-with-bad-signature.pem"
        [cert bad-crl _] (generate-cert-chain-with-crls 1 generate-crl-with-bad-signature)]
    (write-certs-and-crls cert bad-crl test-subpath cert-filename crl-filename)))

(defn write-cert-chain-with-revoked-cert
  [number-of-certs test-subpath]
  (let [certs-filename "cert-chain-with-revoked-cert.pem"
        crls-filename "crl-chain-with-cert-revoked.pem"
        [certs crls] (generate-cert-chain-with-revoked-cert number-of-certs)]
    (write-certs-and-crls certs crls test-subpath certs-filename crls-filename)))

(defn write-bundle-for-manual-validation
  [number-of-certs output-dir]
  (let [[certs crls-with-updates leaf-keys] (generate-cert-chain-with-crls
                                             number-of-certs
                                             generate-multiple-crls)
        original-crls (map :original crls-with-updates)
        new-crls (map :new crls-with-updates)
        newest-crls (map :newest crls-with-updates)]
    (fs/mkdirs output-dir)
    ;; The file names `ca-bundle.pem`, `crls.pem`, and `puppet_ca_key.pem` are used
    ;; in the instructions for setting up an intermediate CA with an external root
    ;; (https://puppet.com/docs/puppet/latest/server/intermediate_ca.html#set-up-puppet-as-an-intermediate-ca-with-an-external-root),
    ;; which should allow for easily copy-pasting the example `import` command.
    (objs->pem! certs (fs/file output-dir "ca-bundle.pem"))
    (objs->pem! original-crls (fs/file output-dir "crls.pem"))
    (objs->pem! new-crls (fs/file output-dir "new-crls.pem"))
    (objs->pem! newest-crls (fs/file output-dir "newest-crls.pem"))
    (key->pem! (get-private-key leaf-keys) (fs/file output-dir "puppet_ca_key.pem"))
    (key->pem! (get-public-key leaf-keys) (fs/file output-dir "puppet_ca_pubkey.pem"))))
