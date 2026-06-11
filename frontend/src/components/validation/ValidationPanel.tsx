import { Button, Card, CardBody, Divider } from '@heroui/react'
import { motion, AnimatePresence } from 'framer-motion'
import { FiPlay, FiCheckCircle } from 'react-icons/fi'
import { useValidation } from '@/hooks/use-validation'
import {
  ValidationPassCard,
  ValidationErrorCard,
} from './ValidationResultCard'
import { TimingReportView } from './TimingReportView'

interface ValidationPanelProps {
  sessionId: string
}

export function ValidationPanel({ sessionId }: ValidationPanelProps) {
  const { validate, data, isLoading, isError, error } = useValidation(sessionId)

  return (
    <motion.div
      className="space-y-4 mt-2"
      data-testid="validation-panel"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.4 }}
    >
      {/* Run button */}
      <Card>
        <CardBody className="flex flex-row items-center justify-between">
          <div>
            <div className="font-semibold">Session Validation</div>
            <div className="text-xs text-default-500">
              Run validation checks to detect event ordering issues, timing anomalies, and structural problems.
            </div>
          </div>
          <motion.div whileHover={{ scale: 1.05 }} whileTap={{ scale: 0.95 }}>
            <Button
              color="primary"
              startContent={isLoading ? undefined : <FiPlay />}
              onPress={() => validate()}
              isLoading={isLoading}
              data-testid="run-validation-btn"
            >
              {isLoading ? 'Running...' : 'Run Validation'}
            </Button>
          </motion.div>
        </CardBody>
      </Card>

      {/* Error state */}
      <AnimatePresence>
        {isError && (
          <motion.div
            initial={{ opacity: 0, y: -10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -10 }}
            transition={{ duration: 0.3 }}
          >
            <Card className="border border-danger/30">
              <CardBody>
                <div className="text-danger text-sm" data-testid="validation-error">
                  Validation failed: {error?.message || 'Unknown error'}
                </div>
              </CardBody>
            </Card>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Results */}
      <AnimatePresence mode="wait">
        {data && (
          <motion.div
            key="results"
            className="space-y-4"
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -20 }}
            transition={{ duration: 0.4 }}
          >
            {(() => {
              const failures = data.results.filter(r => r.type === 'Fail')
              const passes = data.results.filter(r => r.type === 'Pass')
              return (
                <>
                  {/* Summary pass/fail */}
                  <ValidationPassCard
                    failCount={failures.length}
                    totalCount={data.results.length}
                  />

                  {/* Failures */}
                  {failures.length > 0 && (
                    <div className="space-y-2">
                      <h3 className="text-sm font-semibold text-danger">
                        Failures ({failures.length})
                      </h3>
                      {failures.map((result, idx) => (
                        <ValidationErrorCard key={`${result.ruleName}-${idx}`} error={result} index={idx} />
                      ))}
                    </div>
                  )}

                  {/* Passes — compact list, no individual cards */}
                  {passes.length > 0 && (
                    <motion.div
                      className="space-y-2"
                      initial={{ opacity: 0 }}
                      animate={{ opacity: 1 }}
                      transition={{ duration: 0.3 }}
                    >
                      <h3 className="text-sm font-semibold text-success">
                        Passes ({passes.length})
                      </h3>
                      <div className="space-y-1">
                        {passes.map((result, idx) => (
                          <div key={`${result.ruleName}-${idx}`} className="flex items-center gap-1">
                            <FiCheckCircle className="text-success shrink-0" size={12} />
                            <span className="text-xs text-default-500">{result.ruleName}</span>
                          </div>
                        ))}
                      </div>
                    </motion.div>
                  )}

                  <Divider />

                  {/* Timing report */}
                  <div className="space-y-2">
                    <h3 className="text-sm font-semibold text-default-600">Timing Report</h3>
                    <TimingReportView timing={data.timing} />
                  </div>
                </>
              )
            })()}
          </motion.div>
        )}
      </AnimatePresence>

      {/* No results yet */}
      {!data && !isLoading && !isError && (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ duration: 0.4, delay: 0.2 }}
        >
          <Card>
            <CardBody>
              <div className="text-center text-default-400 py-8" data-testid="validation-empty">
                <p className="text-sm">
                  Click "Run Validation" to analyze this session for issues.
                </p>
              </div>
            </CardBody>
          </Card>
        </motion.div>
      )}
    </motion.div>
  )
}
