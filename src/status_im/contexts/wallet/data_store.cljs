(ns status-im.contexts.wallet.data-store
  (:require
    [camel-snake-kebab.extras :as cske]
    [clojure.set :as set]
    [clojure.string :as string]
    [status-im.constants :as constants]
    [status-im.contexts.wallet.common.utils.networks :as network-utils]
    [utils.money :as money]
    [utils.number :as utils.number]
    [utils.transforms :as transforms]))

(defn chain-ids-string->set
  [ids-string]
  (into #{}
        (map utils.number/parse-int)
        (string/split ids-string constants/chain-id-separator)))

(defn chain-ids-set->string
  [ids]
  (string/join constants/chain-id-separator ids))

(defn add-keys-to-account
  [account]
  (-> account
      (assoc :watch-only? (= (:type account) :watch))
      (assoc :default-account? (:wallet account))))

(defn- sanitize-emoji
  "As Desktop uses Twemoji, the emoji received can be an img tag
   with raw emoji in alt attribute. This function help us to extract
   the emoji from it as mobile doesn't support HTML rendering and Twemoji"
  [emoji]
  (if (string/starts-with? emoji "<img")
    (-> (re-find #"alt=\"(.*?)\"" emoji) last)
    emoji))

(defn rpc->account
  [account]
  (-> account
      (set/rename-keys {:prodPreferredChainIds :prod-preferred-chain-ids
                        :testPreferredChainIds :test-preferred-chain-ids
                        :createdAt             :created-at
                        :colorId               :color})
      (update :prod-preferred-chain-ids chain-ids-string->set)
      (update :test-preferred-chain-ids chain-ids-string->set)
      (update :type keyword)
      (update :operable keyword)
      (update :color
              #(if (and (not (keyword? %)) (string/blank? %))
                 constants/account-default-customization-color
                 (keyword %)))
      (update :emoji sanitize-emoji)
      add-keys-to-account))

(defn rpc->accounts
  [accounts]
  (->> (filter #(not (:chat %)) accounts)
       (sort-by :position)
       (map rpc->account)))

(defn <-account
  [account]
  (-> account
      (set/rename-keys {:prod-preferred-chain-ids :prodPreferredChainIds
                        :test-preferred-chain-ids :testPreferredChainIds
                        :color                    :colorId})
      (update :prodPreferredChainIds chain-ids-set->string)
      (update :testPreferredChainIds chain-ids-set->string)
      (dissoc :watch-only? :default-account? :tokens :collectibles)))

(defn- rpc->balances-per-chain
  [token]
  (-> token
      (update :balances-per-chain update-vals #(update % :raw-balance money/bignumber))
      (update :balances-per-chain update-keys (comp utils.number/parse-int name))))

(defn- remove-tokens-with-empty-values
  [tokens]
  (remove
   #(or (string/blank? (:symbol %)) (string/blank? (:name %)))
   tokens))

(defn rpc->tokens
  [tokens]
  (-> tokens
      (update-keys name)
      (update-vals #(cske/transform-keys transforms/->kebab-case-keyword %))
      (update-vals remove-tokens-with-empty-values)
      (update-vals #(mapv rpc->balances-per-chain %))))

(defn rpc->network
  [network]
  (-> network
      (set/rename-keys
       {:Prod                   :prod
        :Test                   :test
        :isTest                 :test?
        :tokenOverrides         :token-overrides
        :rpcUrl                 :rpc-url
        :chainColor             :chain-color
        :chainName              :chain-name
        :nativeCurrencyDecimals :native-currency-decimals
        :relatedChainId         :related-chain-id
        :shortName              :short-name
        :chainId                :chain-id
        :originalFallbackURL    :original-fallback-url
        :originalRpcUrl         :original-rpc-url
        :fallbackURL            :fallback-url
        :blockExplorerUrl       :block-explorer-url
        :nativeCurrencySymbol   :native-currency-symbol
        :nativeCurrencyName     :native-currency-symbol})))

(defn get-keypair-lowest-operability
  [{:keys [accounts]}]
  (cond
    (some #(= (:operable %) :no) accounts)
    :no

    (some #(= (:operable %) :partially) accounts)
    :partially

    :else
    :fully))

(defn- add-keys-to-keypair
  [keypair]
  (assoc keypair :lowest-operability (get-keypair-lowest-operability keypair)))

(defn rpc->keypair
  [keypair]
  (-> keypair
      (update :type keyword)
      (update :accounts #(map rpc->account %))
      add-keys-to-keypair))

(defn rpc->keypairs
  [keypairs]
  (->> (map rpc->keypair keypairs)
       (sort-by #(if (= (:type %) :profile) 0 1))))

(defn- add-keys-to-saved-address
  [saved-address]
  (-> saved-address
      (assoc :network-preferences-names
             (network-utils/network-preference-prefix->network-names (:chain-short-names saved-address)))
      (assoc :ens? (not (string/blank? (:ens saved-address))))))

(defn rpc->saved-address
  [saved-address]
  (-> saved-address
      (set/rename-keys {:chainShortNames  :chain-short-names
                        :isTest           :test?
                        :createdAt        :created-at
                        :colorId          :customization-color
                        :mixedcaseAddress :mixedcase-address
                        :removed          :removed?})
      (update :customization-color keyword)
      add-keys-to-saved-address))

(defn rpc->saved-addresses
  [saved-addresses]
  (map rpc->saved-address saved-addresses))
