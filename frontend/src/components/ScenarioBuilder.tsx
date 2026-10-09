import { useState } from 'react'
import type { 
  CoroutineConfigNode, 
  ActionConfig, 
  ActionType,
  ScenarioConfigRequest 
} from '@/types/api'
import { apiClient } from '@/lib/api-client'
import { useMutation } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'

// Helper to generate unique IDs
let idCounter = 0
const generateId = () => `node-${++idCounter}`

interface CoroutineNodeEditorProps {
  node: CoroutineConfigNode
  onUpdate: (node: CoroutineConfigNode) => void
  onDelete?: () => void
  level?: number
}

function ActionEditor({ 
  action, 
  onUpdate, 
  onDelete 
}: { 
  action: ActionConfig
  onUpdate: (action: ActionConfig) => void
  onDelete: () => void
}) {
  const handleTypeChange = (type: ActionType) => {
    // Reset params when type changes
    let params: Record<string, string> = {}
    if (type === 'delay') {
      params = { durationMs: '1000' }
    } else if (type === 'throw') {
      params = { exceptionType: 'RuntimeException', message: 'Test exception' }
    } else if (type === 'log') {
      params = { message: 'Log message' }
    }
    onUpdate({ type, params })
  }

  const handleParamChange = (key: string, value: string) => {
    onUpdate({
      ...action,
      params: { ...action.params, [key]: value }
    })
  }

  return (
    <div className="flex items-start gap-2 p-2 bg-gray-50 rounded border">
      <select
        value={action.type}
        onChange={(e) => handleTypeChange(e.target.value as ActionType)}
        className="px-2 py-1 border rounded text-sm"
      >
        <option value="delay">Delay</option>
        <option value="throw">Throw Exception</option>
        <option value="log">Log</option>
      </select>

      <div className="flex-1 flex flex-col gap-1">
        {action.type === 'delay' && (
          <input
            type="number"
            value={action.params.durationMs || '1000'}
            onChange={(e) => handleParamChange('durationMs', e.target.value)}
            placeholder="Duration (ms)"
            className="px-2 py-1 border rounded text-sm"
          />
        )}

        {action.type === 'throw' && (
          <>
            <input
              type="text"
              value={action.params.exceptionType || 'RuntimeException'}
              onChange={(e) => handleParamChange('exceptionType', e.target.value)}
              placeholder="Exception type"
              className="px-2 py-1 border rounded text-sm"
            />
            <input
              type="text"
              value={action.params.message || 'Test exception'}
              onChange={(e) => handleParamChange('message', e.target.value)}
              placeholder="Message"
              className="px-2 py-1 border rounded text-sm"
            />
          </>
        )}

        {action.type === 'log' && (
          <input
            type="text"
            value={action.params.message || ''}
            onChange={(e) => handleParamChange('message', e.target.value)}
            placeholder="Log message"
            className="px-2 py-1 border rounded text-sm"
          />
        )}
      </div>

      <button
        onClick={onDelete}
        className="px-2 py-1 text-red-600 hover:bg-red-50 rounded text-sm"
      >
        ✕
      </button>
    </div>
  )
}

function CoroutineNodeEditor({ 
  node, 
  onUpdate, 
  onDelete,
  level = 0 
}: CoroutineNodeEditorProps) {
  const addAction = () => {
    onUpdate({
      ...node,
      actions: [...node.actions, { type: 'delay', params: { durationMs: '1000' } }]
    })
  }

  const updateAction = (index: number, action: ActionConfig) => {
    const newActions = [...node.actions]
    newActions[index] = action
    onUpdate({ ...node, actions: newActions })
  }

  const deleteAction = (index: number) => {
    onUpdate({
      ...node,
      actions: node.actions.filter((_, i) => i !== index)
    })
  }

  const addChild = () => {
    const childId = generateId()
    onUpdate({
      ...node,
      children: [
        ...node.children,
        {
          id: childId,
          label: `child-${node.children.length + 1}`,
          parentId: node.id,
          actions: [],
          children: []
        }
      ]
    })
  }

  const updateChild = (index: number, child: CoroutineConfigNode) => {
    const newChildren = [...node.children]
    newChildren[index] = child
    onUpdate({ ...node, children: newChildren })
  }

  const deleteChild = (index: number) => {
    onUpdate({
      ...node,
      children: node.children.filter((_, i) => i !== index)
    })
  }

  const indent = level * 24

  return (
    <div className="border-l-2 border-blue-300 pl-4" style={{ marginLeft: `${indent}px` }}>
      <div className="mb-4 p-4 bg-white border rounded-lg shadow-sm">
        {/* Node Header */}
        <div className="flex items-center gap-2 mb-3">
          <div className="flex-1 flex items-center gap-2">
            <span className="text-sm font-semibold text-gray-600">Coroutine:</span>
            <input
              type="text"
              value={node.label}
              onChange={(e) => onUpdate({ ...node, label: e.target.value })}
              placeholder="Coroutine label"
              className="flex-1 px-2 py-1 border rounded font-medium"
            />
          </div>
          {onDelete && level > 0 && (
            <button
              onClick={onDelete}
              className="px-3 py-1 text-red-600 hover:bg-red-50 rounded"
            >
              Delete
            </button>
          )}
        </div>

        {/* Actions */}
        <div className="mb-3">
          <div className="flex items-center justify-between mb-2">
            <span className="text-sm font-semibold text-gray-600">Actions:</span>
            <button
              onClick={addAction}
              className="px-2 py-1 text-sm bg-blue-500 text-white rounded hover:bg-blue-600"
            >
              + Add Action
            </button>
          </div>
          <div className="space-y-2">
            {node.actions.length === 0 && (
              <p className="text-sm text-gray-400 italic">No actions yet</p>
            )}
            {node.actions.map((action, index) => (
              <ActionEditor
                key={index}
                action={action}
                onUpdate={(a) => updateAction(index, a)}
                onDelete={() => deleteAction(index)}
              />
            ))}
          </div>
        </div>

        {/* Add Child Button */}
        <button
          onClick={addChild}
          className="w-full px-3 py-2 bg-green-500 text-white rounded hover:bg-green-600"
        >
          + Add Child Coroutine
        </button>
      </div>

      {/* Children */}
      {node.children.map((child, index) => (
        <CoroutineNodeEditor
          key={child.id}
          node={child}
          onUpdate={(c) => updateChild(index, c)}
          onDelete={() => deleteChild(index)}
          level={level + 1}
        />
      ))}
    </div>
  )
}

export function ScenarioBuilder() {
  const navigate = useNavigate()
  const [scenarioName, setScenarioName] = useState('Custom Scenario')
  const [description, setDescription] = useState('')
  const [root, setRoot] = useState<CoroutineConfigNode>({
    id: generateId(),
    label: 'root',
    parentId: null,
    actions: [],
    children: []
  })

  const executeMutation = useMutation({
    mutationFn: async (config: ScenarioConfigRequest) => {
      return apiClient.runCustomScenario(config)
    },
    onSuccess: (response) => {
      console.log('Scenario executed successfully:', response)
      // Navigate to the session details page
      navigate({ to: '/sessions/$sessionId', params: { sessionId: response.sessionId } })
    },
    onError: (error) => {
      console.error('Error executing scenario:', error)
    }
  })

  const handleExecute = () => {
    const config: ScenarioConfigRequest = {
      name: scenarioName,
      description: description || null,
      sessionId: null, // Create new session
      root
    }
    executeMutation.mutate(config)
  }

  const handleExportJson = () => {
    const config: ScenarioConfigRequest = {
      name: scenarioName,
      description: description || null,
      sessionId: null,
      root
    }
    const json = JSON.stringify(config, null, 2)
    const blob = new Blob([json], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${scenarioName.replace(/\s+/g, '-').toLowerCase()}.json`
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div className="max-w-6xl mx-auto p-6">
      <div className="mb-6">
        <h1 className="text-3xl font-bold mb-2">Scenario Builder</h1>
        <p className="text-gray-600">
          Build custom coroutine scenarios with delays, exceptions, and nested hierarchies
        </p>
      </div>

      {/* Scenario Metadata */}
      <div className="mb-6 p-4 bg-white border rounded-lg shadow-sm">
        <div className="space-y-3">
          <div>
            <label className="block text-sm font-semibold text-gray-600 mb-1">
              Scenario Name
            </label>
            <input
              type="text"
              value={scenarioName}
              onChange={(e) => setScenarioName(e.target.value)}
              className="w-full px-3 py-2 border rounded"
              placeholder="Enter scenario name"
            />
          </div>
          <div>
            <label className="block text-sm font-semibold text-gray-600 mb-1">
              Description (optional)
            </label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              className="w-full px-3 py-2 border rounded"
              placeholder="Describe what this scenario demonstrates"
              rows={2}
            />
          </div>
        </div>
      </div>

      {/* Coroutine Tree Builder */}
      <div className="mb-6">
        <h2 className="text-xl font-bold mb-3">Coroutine Hierarchy</h2>
        <CoroutineNodeEditor
          node={root}
          onUpdate={setRoot}
        />
      </div>

      {/* Action Buttons */}
      <div className="flex gap-3">
        <button
          onClick={handleExecute}
          disabled={executeMutation.isPending}
          className="px-6 py-3 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:bg-gray-400 font-semibold"
        >
          {executeMutation.isPending ? 'Executing...' : '▶ Execute Scenario'}
        </button>

        <button
          onClick={handleExportJson}
          className="px-6 py-3 bg-gray-600 text-white rounded-lg hover:bg-gray-700 font-semibold"
        >
          📥 Export JSON
        </button>

        <button
          // Back to the sessions list: /scenarios only redirects there (#139).
          onClick={() => navigate({ to: '/' })}
          className="px-6 py-3 bg-gray-300 text-gray-700 rounded-lg hover:bg-gray-400 font-semibold"
        >
          Cancel
        </button>
      </div>

      {/* Error Display */}
      {executeMutation.isError && (
        <div className="mt-4 p-4 bg-red-50 border border-red-200 rounded-lg">
          <p className="text-red-700 font-semibold">Error executing scenario:</p>
          <p className="text-red-600 text-sm mt-1">
            {executeMutation.error.message}
          </p>
        </div>
      )}

      {/* Success Display */}
      {executeMutation.isSuccess && (
        <div className="mt-4 p-4 bg-green-50 border border-green-200 rounded-lg">
          <p className="text-green-700 font-semibold">✅ Scenario executed successfully!</p>
          <p className="text-green-600 text-sm mt-1">
            Session: {executeMutation.data.sessionId} | 
            Coroutines: {executeMutation.data.coroutineCount} | 
            Events: {executeMutation.data.eventCount}
          </p>
        </div>
      )}

      {/* Help Section */}
      <div className="mt-8 p-4 bg-blue-50 border border-blue-200 rounded-lg">
        <h3 className="font-semibold text-blue-900 mb-2">💡 Quick Guide</h3>
        <ul className="text-sm text-blue-800 space-y-1">
          <li>• <strong>Delay</strong>: Suspends the coroutine for a specified duration (milliseconds)</li>
          <li>• <strong>Throw Exception</strong>: Throws an exception to test failure handling</li>
          <li>• <strong>Log</strong>: Prints a message (useful for debugging)</li>
          <li>• <strong>Add Child Coroutine</strong>: Creates a nested child coroutine</li>
          <li>• Actions execute sequentially within each coroutine</li>
          <li>• Parent coroutines wait for all children to complete (structured concurrency)</li>
        </ul>
      </div>
    </div>
  )
}

