(ns org.clojuriststogether.app.cashflow-xlsx
  "Small, dependency-free XLSX writer for the forecast's rectangular tables."
  (:require [clojure.string :as str]
            [org.clojuriststogether.app.cashflow :as cf])
  (:import (java.io ByteArrayOutputStream)
           (java.util.zip ZipOutputStream ZipEntry)))

(defn xml [x]
  (-> (str x) (str/replace #"[\x00-\x08\x0B\x0C\x0E-\x1F]" "")
      (str/escape {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&apos;"})))

(defn column [n]
  (loop [n (inc n) result ""]
    (if (zero? n) result
      (recur (quot (dec n) 26) (str (char (+ 65 (mod (dec n) 26))) result)))))

(defn cell [r c value]
  (let [ref (str (column c) r)]
    (cond
      (number? value) (str "<c r=\"" ref "\" s=\"1\"><v>" value "</v></c>")
      (map? value) (str "<c r=\"" ref "\" s=\"1\"><f>" (xml (:formula value)) "</f><v>" (:value value) "</v></c>")
      :else (str "<c r=\"" ref "\" s=\"2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" (xml value) "</t></is></c>"))))

(defn sheet [rows]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
       "<sheetPr><outlinePr summaryBelow=\"0\"/></sheetPr>"
       "<sheetViews><sheetView workbookViewId=\"0\"><pane xSplit=\"2\" ySplit=\"1\" topLeftCell=\"C2\" activePane=\"bottomRight\" state=\"frozen\"/></sheetView></sheetViews>"
       (if (= "Assumptions" (ffirst rows))
         "<cols><col min=\"1\" max=\"1\" width=\"42\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"110\" customWidth=\"1\"/></cols><sheetData>"
         "<cols><col min=\"1\" max=\"1\" width=\"38\" customWidth=\"1\"/><col min=\"2\" max=\"62\" width=\"16\" customWidth=\"1\"/></cols><sheetData>")
       (apply str (map-indexed
                    (fn [i row]
                      (str "<row r=\"" (inc i) "\" ht=\"" (if (= "Assumptions" (ffirst rows)) 75 32) "\" customHeight=\"1\"" (when (:detail (meta row)) " outlineLevel=\"1\"") ">"
                           (apply str (map-indexed #(cell (inc i) %1 %2) row)) "</row>")) rows))
       "</sheetData></worksheet>"))

(defn raw-forecast-rows [{:keys [months rows] :as forecast}]
  (into [(into ["Member" "Currency"] (map #(str % " " (cf/month-kind forecast %)) months))]
        (mapcat
          (fn [[currency members]]
            (let [developers (filter #(= "developer" (:type %)) members)
                  companies (filter #(= "company" (:type %)) members)
                  other (filter #(= "other" (:type %)) members)]
              (concat [(into ["Baseline net receipts" (str/upper-case currency)]
                             (map #(cf/major currency %) (cf/total members :baseline (count months))))]
                      [(into ["Scenario net receipts" (str/upper-case currency)]
                             (map #(cf/major currency %) (cf/total members :values (count months))))]
                      [(with-meta (into ["Developer members" (str/upper-case currency)]
                                        (map #(cf/major currency %) (cf/total developers :values (count months)))) {:detail true})]
                      (for [member companies]
                        (with-meta (into [(:name member) (str/upper-case currency)]
                                         (map #(cf/major currency %) (:values member))) {:detail true}))
                      (when (seq other)
                        [(with-meta (into ["Other subscription receipts" (str/upper-case currency)]
                                          (map #(cf/major currency %) (cf/total other :values (count months)))) {:detail true})]))))
          (sort-by key (group-by :currency rows)))))

(defn forecast-rows [forecast]
  (let [rows (raw-forecast-rows forecast)]
    (map-indexed
      (fn [i row]
        (if (= "Scenario net receipts" (first row))
          (let [n (count (take-while #(-> % meta :detail) (drop (inc i) rows)))]
            (mapv (fn [c value]
                    (if (and (>= c 2) (number? value))
                      {:formula (str "SUM(" (column c) (+ i 2) ":" (column c) (+ i 1 n) ")") :value value}
                      value)) (range) row)) row)) rows)))

(defn workbook [forecast generated-at]
  (let [notes (concat [["Assumptions" "Value"] ["Generated at (UTC)" (str generated-at)]
                       ["Source" "Stripe subscriptions and member database"]
                       ["Estimated fee method" cf/fee-method]
                       ["Fallback fee (%)" (get-in forecast [:options :fee-percent] 0)]
                       ["Fallback fixed fee per payment (each currency)" (get-in forecast [:options :fee-fixed] 0)]
                       ["Timing" cf/timing]
                       ["Forecast basis" cf/assumptions]
                       ["Cancellation scenarios" "No receipts from the selected month onwards; no Stripe changes."]]
                      (for [r (:rows forecast) :when (:cancel-month r)]
                        [(str (:name r) " (member " (:id r) ")") (str (:cancel-month r))])
                      (for [r (:rows forecast)
                            :let [samples (get-in forecast [:options :fee-models
                                                           [(:customer r) (:currency r)]])]
                            :when (:customer r)]
                        [(str (:name r) " fee history (" (str/upper-case (:currency r)) ")")
                         (if (seq samples)
                           (str (count samples) " payments; gross " (cf/major (:currency r) (reduce + (map :gross samples)))
                                "; fees " (cf/major (:currency r) (reduce + (map :fee samples)))) "Fallback fees")])
                      (map (fn [{:keys [name url monthly billing reason]}]
                             [name (str reason "; "
                                        (if monthly
                                          (str (str/upper-case (:currency monthly)) " "
                                               (format "%.2f" (double (cf/major (:currency monthly) (:amount monthly)))) "/month before fees")
                                          "Monthly amount unavailable")
                                        "; " (:amount-label billing) ": "
                                        (if (some? (:amount billing))
                                          (str (str/upper-case (:currency billing)) " " (cf/major (:currency billing) (:amount billing))) "Unavailable")
                                        "; " (:due-label billing) ": " (or (:due billing) (:due-note billing) "No due date set")
                                        (when (:invoice-date billing) (str "; Invoice issued " (:invoice-date billing)))
                                        (when (:next-attempt billing) (str "; Next payment attempt " (:next-attempt billing)))
                                        "; " url)]) (:warnings forecast)))
        parts {"[Content_Types].xml" "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>"
               "_rels/.rels" "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"
               "xl/workbook.xml" "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Cashflow\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"Assumptions\" sheetId=\"2\" r:id=\"rId2\"/></sheets></workbook>"
               "xl/_rels/workbook.xml.rels" "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/><Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"
               "xl/styles.xml" "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf/></cellStyleXfs><cellXfs count=\"3\"><xf fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"4\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/><xf fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment wrapText=\"1\" vertical=\"center\"/></xf></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"
               "xl/worksheets/sheet1.xml" (sheet (forecast-rows forecast))
               "xl/worksheets/sheet2.xml" (sheet notes)}]
    (with-open [out (ByteArrayOutputStream.)]
      (with-open [zip (ZipOutputStream. out)]
        (doseq [[path content] parts]
          (.putNextEntry zip (ZipEntry. path))
          (.write zip (.getBytes ^String content "UTF-8"))
          (.closeEntry zip)))
      (.toByteArray out))))
