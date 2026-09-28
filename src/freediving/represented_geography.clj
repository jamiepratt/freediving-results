(ns freediving.represented-geography
  "Private, explicit IOC sports-continent mapping for the first DNF peer set.
   These are source representation codes, not citizenship or sovereignty claims.
   PHL is the exact printed AIDA token, bridged by UN code and OCA membership;
   it is not represented as an IOC NOC code.")

(def policy :ioc-noc-first-dnf-2026-09-28-v1)

(def evidence
  {:checked-on "2026-09-28"
   :ioc-2019 {:url "https://olympicsolidarity.touchlines.com/annualreport2019/fr/static/_content/olympic_solidarity_annual_report_2019.pdf"
              :pages "158-159"}
   :ioc-2021 {:url "https://library.olympics.com/network/digitalCollection/DigitalCollectionAttachmentDownloadHandler.ashx?documentId=2875797&parentDocumentId=2875796&skipCopyright=true&skipWatermark=true"
              :section "Olympic Solidarity annual report, NOCs by continent"}
   :eoc-current {:url "https://www.eurolympic.org/enocs/"
                 :section "European National Olympic Committees"}
   :cmas-continent-rule {:url "https://archives.cmas.org/document?fileId=5033&language=1&sessionId="
                         :section "CMAS continental grouping procedure"}
   :aida-selected-view {:url "https://www.aidainternational.org/StartList/4852"
                        :retained-html-sha256 "67933b6afa56c7c4cff1df14b9b415d2e32d33f49feb10f24be46d4b59fa3e93"
                        :active-day "day_5"
                        :selected-date "2026-06-03"
                        :hidden-day-nr "5"}
   :phl-crosswalk {:source-token "PHL"
                   :publisher :AIDA
                   :source-view :aida-selected-view
                   :un-code {:url "https://unstats.un.org/unsd/methodology/m49/"
                             :entry "Philippines | 608 | PHL"}
                   :oca-member {:url "https://oca.asia/noc/54-phi-philippines.html"
                                :entry "Philippines, sports short name PHI, Asian NOC"}}
   :unresolved-tokens
   {"CMAS1" {:source {:url "https://www.cmas.org/document/2026,-cmas-world-championship-freediving-indoor/download.html"
                      :position "senior women DNF, page 5, printed CMAS1"}
             :corroboration {:url "https://www.cmas.org/media/com_eventbooking/2024%20CMAS%2022nd%20World%20Championship%20Finswimming%20Open%20Water%20-%20Results.pdf"
                             :note "CMAS1 also appears as a publisher label in official results"}
             :reason "No source-specific represented-country proof"}
    "AIN" {:source {:view :aida-selected-view
                    :position "selected 2026-06-03 women DNF, printed AIN"}
           :reason "No event-specific represented-country proof"}}})

(def ^:private by-continent
  {:europe #{"ARM" "AUT" "AZE" "BEL" "CRO" "CZE" "DEN" "FIN" "FRA"
             "GBR" "GER" "GRE" "HUN" "ITA" "LAT" "LUX" "MDA" "NED"
             "NOR" "POL" "POR" "ROU" "SUI" "SVK" "SWE" "TUR" "UKR"}
   :asia #{"CHN" "HKG" "INA" "IND" "JPN" "KOR" "LBN" "MAS" "TPE"}
   :africa #{"EGY" "RSA"}
   :americas #{"BRA" "CAN" "COL" "ECU" "PER" "USA"}
   :oceania #{"AUS"}})

(def ^:private by-code
  (into {} (for [[continent codes] by-continent
                 code codes]
             [code continent])))

(def ^:private source-code-crosswalk
  {"PHL" :asia})

(defn sports-continent
  "Return the IOC sports continent for an exact supported source token, else nil.
   PHL is an explicit UN/OCA crosswalk; AIN and CMAS1 remain unresolved."
  [represented-country]
  (or (get by-code represented-country)
      (get source-code-crosswalk represented-country)))
