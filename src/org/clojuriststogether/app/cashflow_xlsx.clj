(ns org.clojuriststogether.app.cashflow-xlsx
  (:require [clojure.string :as str]
            [org.clojuriststogether.app.cashflow :as cf])
  (:import (java.io ByteArrayOutputStream)
           (org.apache.poi.ss.usermodel VerticalAlignment)
           (org.apache.poi.ss.util CellReference)
           (org.apache.poi.xssf.usermodel XSSFWorkbook)))

(defn- raw-forecast-rows [{:keys [months rows] :as forecast}]
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

(defn- forecast-rows [forecast]
  (let [rows (raw-forecast-rows forecast)]
    (map-indexed
     (fn [i row]
       (if (= "Scenario net receipts" (first row))
         (let [n (count (take-while #(-> % meta :detail) (drop (inc i) rows)))]
           (mapv (fn [c value]
                   (if (and (>= c 2) (number? value))
                     {:formula (str "SUM(" (CellReference/convertNumToColString c) (+ i 2) ":" (CellReference/convertNumToColString c) (+ i 1 n) ")")}
                     value)) (range) row)) row)) rows)))

(defn- assumption-rows [forecast generated-at]
  (concat [["Assumptions" "Value"] ["Generated at (UTC)" (str generated-at)]
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
                            "; " url)]) (:warnings forecast))))

(defn- add-sheet! [^XSSFWorkbook workbook name rows widths row-height]
  (let [sheet (.createSheet workbook name)
        number-style (.createCellStyle workbook)
        text-style (.createCellStyle workbook)]
    (.setDataFormat number-style (.getFormat (.createDataFormat workbook) "#,##0.00"))
    (.setWrapText text-style true)
    (.setVerticalAlignment text-style VerticalAlignment/CENTER)
    (.createFreezePane sheet 2 1)
    (.setRowSumsBelow sheet false)
    (doseq [[index width] (map-indexed vector widths)]
      (.setColumnWidth sheet index (* 256 width)))
    (doseq [[index values] (map-indexed vector rows)]
      (let [row (.createRow sheet index)]
        (.setHeightInPoints row (float row-height))
        (doseq [[column value] (map-indexed vector values)]
          (let [cell (.createCell row column)]
            (cond
              (map? value)
              (do (.setCellFormula cell (:formula value))
                  (.setCellStyle cell number-style))

              (number? value)
              (do (.setCellValue cell (double value))
                  (.setCellStyle cell number-style))

              :else
              (do (.setCellValue cell (str value))
                  (.setCellStyle cell text-style)))))))
    (doseq [group (partition-by #(boolean (:detail (meta (second %))))
                                (map-indexed vector rows))
            :when (:detail (meta (second (first group))))]
      (.groupRow sheet (ffirst group) (first (last group))))))

(defn workbook [forecast generated-at]
  (with-open [workbook (XSSFWorkbook.)
              out (ByteArrayOutputStream.)]
    (add-sheet! workbook "Cashflow" (forecast-rows forecast)
                (cons 38 (repeat (inc (count (:months forecast))) 16)) 32)
    (add-sheet! workbook "Assumptions" (assumption-rows forecast generated-at) [42 110] 75)
    (.evaluateAll (.createFormulaEvaluator (.getCreationHelper workbook)))
    (.write workbook out)
    (.toByteArray out)))
