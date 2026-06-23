import { Card, CardBody, Chip } from '@heroui/react'
import { motion } from 'framer-motion'
import { FiCheckCircle, FiXCircle, FiAlertTriangle } from 'react-icons/fi'
import type { ValidationRuleResult } from '@/types/api'

// Legacy type kept for ValidationWarningCard (unused — backend has no Warning type)
interface LegacyValidationWarning {
  code: string
  message: string
  eventSeq?: number
  coroutineId?: string
  suggestion?: string
}

interface ValidationErrorCardProps {
  error: ValidationRuleResult
  index?: number
}

export function ValidationErrorCard({ error, index = 0 }: ValidationErrorCardProps) {
  return (
    <motion.div
      initial={{ opacity: 0, x: -20, scale: 0.95 }}
      animate={{
        opacity: 1,
        x: [0, -2, 2, -2, 2, 0],
        scale: 1,
      }}
      transition={{
        opacity: { duration: 0.3, delay: index * 0.05 },
        x: { duration: 0.4, delay: 0.3 + index * 0.05 },
        scale: { duration: 0.3, delay: index * 0.05 },
      }}
    >
      <motion.div
        animate={{
          boxShadow: [
            '0 0 0 rgba(243, 18, 96, 0)',
            '0 0 8px rgba(243, 18, 96, 0.15)',
            '0 0 0 rgba(243, 18, 96, 0)',
          ],
        }}
        transition={{ duration: 2, repeat: Infinity }}
        className="rounded-xl"
      >
        <Card shadow="sm" className="border border-danger/30" data-testid="validation-error-card">
          <CardBody className="flex flex-row items-start gap-3">
            <FiXCircle className="text-danger shrink-0 mt-0.5" size={18} />
            <div className="space-y-1">
              <div className="flex items-center gap-2">
                <Chip size="sm" variant="flat" color="danger">{error.ruleName}</Chip>
              </div>
              <p className="text-sm text-default-700">{error.message}</p>
              {error.details && (
                <p className="text-xs text-default-500">{error.details}</p>
              )}
            </div>
          </CardBody>
        </Card>
      </motion.div>
    </motion.div>
  )
}

interface ValidationWarningCardProps {
  warning: LegacyValidationWarning
  index?: number
}

export function ValidationWarningCard({ warning, index = 0 }: ValidationWarningCardProps) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3, delay: index * 0.05 }}
    >
      <motion.div
        animate={{
          boxShadow: [
            '0 0 0 rgba(245, 165, 36, 0)',
            '0 0 8px rgba(245, 165, 36, 0.15)',
            '0 0 0 rgba(245, 165, 36, 0)',
          ],
        }}
        transition={{ duration: 2, repeat: Infinity }}
        className="rounded-xl"
      >
        <Card shadow="sm" className="border border-warning/30" data-testid="validation-warning-card">
          <CardBody className="flex flex-row items-start gap-3">
            <FiAlertTriangle className="text-warning shrink-0 mt-0.5" size={18} />
            <div className="space-y-1">
              <div className="flex items-center gap-2">
                <Chip size="sm" variant="flat" color="warning">{warning.code}</Chip>
              </div>
              <p className="text-sm text-default-700">{warning.message}</p>
              {warning.suggestion && (
                <p className="text-xs text-default-500">{warning.suggestion}</p>
              )}
              {(warning.eventSeq != null || warning.coroutineId) && (
                <div className="flex gap-2 text-xs text-default-400">
                  {warning.eventSeq != null && <span>Event #{warning.eventSeq}</span>}
                  {warning.coroutineId && <span className="font-mono">{warning.coroutineId}</span>}
                </div>
              )}
            </div>
          </CardBody>
        </Card>
      </motion.div>
    </motion.div>
  )
}

interface ValidationPassCardProps {
  failCount: number
  totalCount: number
}

export function ValidationPassCard({ failCount, totalCount }: ValidationPassCardProps) {
  const valid = failCount === 0
  return (
    <motion.div
      initial={{ scale: 0.9, opacity: 0 }}
      animate={{ scale: 1, opacity: 1 }}
      transition={{ type: 'spring', stiffness: 300, damping: 25 }}
    >
      <motion.div
        animate={
          valid
            ? {
                boxShadow: [
                  '0 0 0 rgba(23, 201, 100, 0)',
                  '0 0 12px rgba(23, 201, 100, 0.2)',
                  '0 0 0 rgba(23, 201, 100, 0)',
                ],
              }
            : {
                boxShadow: [
                  '0 0 0 rgba(243, 18, 96, 0)',
                  '0 0 12px rgba(243, 18, 96, 0.2)',
                  '0 0 0 rgba(243, 18, 96, 0)',
                ],
              }
        }
        transition={{ duration: 2, repeat: Infinity }}
        className="rounded-xl"
      >
        <Card shadow="sm" data-testid="validation-summary">
          <CardBody className="flex flex-row items-center gap-3">
            {valid ? (
              <>
                <FiCheckCircle className="text-success" size={24} />
                <div>
                  <div className="font-semibold text-success">All Rules Passed</div>
                  <div className="text-xs text-default-500">
                    {totalCount} rule{totalCount !== 1 ? 's' : ''} checked
                  </div>
                </div>
              </>
            ) : (
              <>
                <FiXCircle className="text-danger" size={24} />
                <div>
                  <div className="font-semibold text-danger">{failCount} Rule{failCount !== 1 ? 's' : ''} Failed</div>
                  <div className="text-xs text-default-500">
                    {failCount} of {totalCount} rules failed
                  </div>
                </div>
              </>
            )}
          </CardBody>
        </Card>
      </motion.div>
    </motion.div>
  )
}
