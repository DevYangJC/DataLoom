import Excel from 'exceljs'
import FileSaver from 'file-saver'

export async function exportExcel(sheets, fileName = 'DataLoom') {
  const workbook = new Excel.Workbook()
  const sheetList = Array.isArray(sheets) ? sheets : [sheets]

  sheetList.forEach((sheet, index) => {
    if (!sheet || !Array.isArray(sheet.data) || sheet.data.length === 0) return

    const worksheet = workbook.addWorksheet(sheet.name || `Sheet${index + 1}`)
    applyCells(sheet.data, worksheet)
    applyMerges(sheet.config?.merge || {}, worksheet)
    applyBorders(sheet.config?.borderInfo || [], worksheet)
  })

  if (workbook.worksheets.length === 0) {
    workbook.addWorksheet('Sheet1')
  }

  const buffer = await workbook.xlsx.writeBuffer()
  const blob = new Blob([buffer], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;charset=utf-8'
  })
  FileSaver.saveAs(blob, `${sanitizeFileName(fileName)}.xlsx`)
}

function applyCells(rows, worksheet) {
  rows.forEach((row, rowIndex) => {
    if (!Array.isArray(row)) return

    row.forEach((cell, columnIndex) => {
      if (!cell) return

      const target = worksheet.getCell(rowIndex + 1, columnIndex + 1)
      const fill = convertFill(cell.bg)

      if (fill) target.fill = fill
      target.font = convertFont(cell)
      target.alignment = convertAlignment(cell)
      target.value = convertValue(cell)
    })
  })
}

function applyMerges(mergeConfig, worksheet) {
  Object.values(mergeConfig).forEach((merge) => {
    if (!merge) return
    worksheet.mergeCells(
      merge.r + 1,
      merge.c + 1,
      merge.r + merge.rs,
      merge.c + merge.cs
    )
  })
}

function applyBorders(borderInfo, worksheet) {
  if (!Array.isArray(borderInfo)) return

  borderInfo.forEach((entry) => {
    if (entry.rangeType === 'range') {
      const border = convertBorder(entry.borderType, entry.style, entry.color)
      const range = entry.range?.[0]
      if (!range) return

      for (let r = range.row[0] + 1; r <= range.row[1] + 1; r += 1) {
        for (let c = range.column[0] + 1; c <= range.column[1] + 1; c += 1) {
          worksheet.getCell(r, c).border = border
        }
      }
    }

    if (entry.rangeType === 'cell' && entry.value) {
      const { row_index: rowIndex, col_index: columnIndex, ...borders } = entry.value
      worksheet.getCell(rowIndex + 1, columnIndex + 1).border = convertCellBorders(borders)
    }
  })
}

function convertValue(cell) {
  if (cell.f) return { formula: cell.f, result: cell.v }
  if (!cell.v && cell.ct?.s) return cell.ct.s.map((item) => item.v).join('')
  return cell.v ?? ''
}

function convertFill(bg) {
  if (!bg) return null
  return {
    type: 'pattern',
    pattern: 'solid',
    fgColor: { argb: stripColor(bg) }
  }
}

function convertFont(cell) {
  const fonts = {
    0: 'Microsoft YaHei',
    1: 'SimSun',
    2: 'SimHei',
    3: 'KaiTi',
    4: 'FangSong',
    9: 'Arial',
    10: 'Times New Roman',
    11: 'Tahoma',
    12: 'Verdana'
  }

  return {
    name: typeof cell.ff === 'number' ? fonts[cell.ff] || 'Microsoft YaHei' : cell.ff || 'Microsoft YaHei',
    family: 1,
    size: cell.fs || 10,
    color: { argb: stripColor(cell.fc || '#000000') },
    bold: Boolean(cell.bl),
    italic: Boolean(cell.it),
    underline: Boolean(cell.ul),
    strike: Boolean(cell.cl)
  }
}

function convertAlignment(cell) {
  const vertical = { 0: 'middle', 1: 'top', 2: 'bottom', default: 'top' }
  const horizontal = { 0: 'center', 1: 'left', 2: 'right', default: 'left' }
  const wrapText = { 0: false, 1: false, 2: true, default: false }
  const textRotation = { 0: 0, 1: 45, 2: -45, 3: 'vertical', 4: 90, 5: -90, default: 0 }

  return {
    vertical: vertical[cell.vt ?? 'default'],
    horizontal: horizontal[cell.ht ?? 'default'],
    wrapText: wrapText[cell.tb ?? 'default'],
    textRotation: textRotation[cell.tr ?? 'default']
  }
}

function convertBorder(borderType, style = 1, color = '#000000') {
  const typeMap = {
    'border-all': 'all',
    'border-top': 'top',
    'border-right': 'right',
    'border-bottom': 'bottom',
    'border-left': 'left'
  }
  const side = typeMap[borderType]
  const template = {
    style: convertBorderStyle(style),
    color: { argb: stripColor(color) }
  }

  if (side === 'all') {
    return { top: template, right: template, bottom: template, left: template }
  }
  return side ? { [side]: template } : {}
}

function convertCellBorders(borders) {
  const sideMap = { l: 'left', r: 'right', b: 'bottom', t: 'top' }
  const result = {}

  Object.entries(borders).forEach(([key, value]) => {
    const side = sideMap[key]
    if (!side || !value) return
    result[side] = {
      style: convertBorderStyle(value.style),
      color: { argb: stripColor(value.color || '#000000') }
    }
  })

  return result
}

function convertBorderStyle(style = 1) {
  const styles = {
    0: 'none',
    1: 'thin',
    2: 'hair',
    3: 'dotted',
    4: 'dashDot',
    5: 'dashDot',
    6: 'dashDotDot',
    7: 'double',
    8: 'medium',
    9: 'mediumDashed',
    10: 'mediumDashDot',
    11: 'mediumDashDotDot',
    12: 'slantDashDot',
    13: 'thick'
  }
  return styles[style] || 'thin'
}

function stripColor(color) {
  if (!color || color.startsWith('rgb')) return '000000'
  return color.replace('#', '').toUpperCase()
}

function sanitizeFileName(fileName) {
  return String(fileName).replace(/[\\/:*?"<>|]/g, '_') || 'DataLoom'
}
