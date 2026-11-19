// 字段映射配置
const fieldMappings = {
  line: {
    lineId: ['唯一标识码', '管段编号', 'id', 'gid', 'objectid', 'code'],
    lineStart: ['管段起点编号', '起点', 'start', 'qdbh', 'qidian'],
    lineEnd: ['管段终点编号', '终点', 'end', 'zdbh', 'zhongdian'],
    lineStartElev: ['管道起点高程', '起点高程', 'qg', 'start_elev', 'qdgc'],
    lineEndElev: ['管道终点高程', '终点高程', 'zg', 'end_elev', 'zdgc'],
    lineStartDepth: ['管段起点埋深', '起点埋深', 'start_depth', 'qms'],
    lineEndDepth: ['管段终点埋深', '终点埋深', 'end_depth', 'zms'],
    lineDiameter: ['管径', 'diameter', 'gj', 'dj'],
  },
  point: {
    pointId: ['唯一标识码', 'id', 'gid', 'objectid', 'code'],
    pointCode: ['管点编号', '编号', 'code', 'gd', 'gdbh'],
    pointAttach: ['附属物', 'attach', 'fsw'],
    pointAttachType: ['附属物类型', 'attach_type', 'fswlx', 'attachType'],
    pointFeature: ['特征', 'feature', 'tz'],
    pointFeatureType: ['特征类型', 'feature_type', 'tzlx', 'featureType'],
    pointDirection: ['方向', 'direction', 'dir', 'angle', 'azimuth', 'fangxiang'],
    pointCoverType: ['井盖类型', 'cover_type', 'jglx'],
    pointCoverRule: ['井盖规格', 'cover_rule', 'jggg'],
    pointCoverMat: ['井盖材质', 'cover_mat', 'jgzl'],
    pointGround: ['地面高程', 'ground', 'g', 'dm', 'dmgc'],
    pointDepth: ['井深', 'depth', 'js'],
  }
};

// 全局变量存储字段数据
let lineFields = [];
let pointFields = [];
let lineUploaded = false;  // 标记管线是否已上传
let pointUploaded = false; // 标记管点是否已上传
let uploadedTextureFileName = '';

// 刷新附属物设置区域显示
function refreshPointAttachSettingsVisibility() {
  const section = document.getElementById('pointAttachSettings');
  const pointAttachSelect = document.getElementById('pointAttach');
  const attachFieldSelect = document.getElementById('attachFieldSelect');
  const chosenField = (attachFieldSelect && attachFieldSelect.value) || (pointAttachSelect && pointAttachSelect.value) || '';
  if (chosenField) {
    section.style.display = '';
    // 优先 attachFieldSelect 的选择
    const fieldToLoad = attachFieldSelect && attachFieldSelect.value ? attachFieldSelect.value : pointAttachSelect.value;
    loadPointAttachValues(fieldToLoad);
  } else {
    section.style.display = 'none';
  }
}

// 页面加载完成后初始化
window.onload = function() {
  initializeEventListeners();
};

// 初始化事件监听器
function initializeEventListeners() {
  // 管线文件上传
  document.getElementById('uploadLine').addEventListener('change', handleLineUpload);
  
  // 管点文件上传
  document.getElementById('uploadPoint').addEventListener('change', handlePointUpload);
  
  // 添加点击事件监听器，用于二次上传时清空
  document.getElementById('uploadLine').addEventListener('click', function() {
    if (lineUploaded) {
      // 如果已经上传过，点击时清空
      this.value = '';
      lineUploaded = false;
      // 清空管线字段映射
      clearLineFields();
    }
  });
  
  document.getElementById('uploadPoint').addEventListener('click', function() {
    if (pointUploaded) {
      // 如果已经上传过，点击时清空
      this.value = '';
      pointUploaded = false;
      // 清空管点字段映射
      clearPointFields();
    }
  });
  
  // 添加字段选择变化监听器，实时校验
  addFieldChangeListeners();

  // 贴图图片上传逻辑
  const pipeTextureInput = document.getElementById('pipeTexture');
  pipeTextureInput.addEventListener('change', function() {
    if (pipeTextureInput.files.length > 0) {
      const file = pipeTextureInput.files[0];
      const formData = new FormData();
      formData.append('file', file);
      fetch('/api/uploadTexture', {
        method: 'POST',
        body: formData
      })
      .then(response => response.json())
      .then(data => {
        if (data.success) {
          uploadedTextureFileName = data.fileName;
          alert('贴图上传成功！');
        } else {
          alert('贴图上传失败');
        }
      })
      .catch(() => alert('贴图上传失败'));
    }
  });

  // 方案切换：颜色 <-> 贴图
  const schemeSel = document.getElementById('renderScheme');
  const pipeMaterialLabel = document.getElementById('pipeMaterialLabel');
  const pipeMaterialHint = document.getElementById('pipeMaterialHint');
  const pipeColor = document.getElementById('pipeColor');
  const pipeColorPicker = document.getElementById('pipeColorPicker');
  const pipeTexture = document.getElementById('pipeTexture');
  const pipeTexturePreview = document.getElementById('pipeTexturePreview');

  function updateMaterialUI() {
    const scheme = schemeSel.value;
    if (scheme === 'color') {
      pipeMaterialLabel.textContent = '管线材质(颜色)';
      pipeMaterialHint.textContent = '(选择颜色)';
      pipeColor.style.display = '';
      pipeColorPicker.style.display = '';
      pipeTexture.style.display = 'none';
      pipeTexturePreview.style.display = 'none';
    } else {
      pipeMaterialLabel.textContent = '管线材质(贴图)';
      pipeMaterialHint.textContent = '(选择图片)';
      pipeColor.style.display = 'none';
      pipeColorPicker.style.display = 'none';
      pipeTexture.style.display = '';
      if (pipeTexture.files && pipeTexture.files[0]) {
        const url = URL.createObjectURL(pipeTexture.files[0]);
        pipeTexturePreview.src = url;
        pipeTexturePreview.style.display = '';
      } else {
        pipeTexturePreview.style.display = 'none';
      }
    }
  }
  schemeSel.addEventListener('change', updateMaterialUI);
  updateMaterialUI();

  // 贴图选择预览
  pipeTexture.addEventListener('change', function() {
    if (pipeTexture.files && pipeTexture.files[0]) {
      const url = URL.createObjectURL(pipeTexture.files[0]);
      pipeTexturePreview.src = url;
      pipeTexturePreview.style.display = '';
    } else {
      pipeTexturePreview.style.display = 'none';
    }
  });

  // 当映射了"附属物"字段时，展示附属物设置表
  const pointAttachSelect = document.getElementById('pointAttach');
  const attachFieldSelect = document.getElementById('attachFieldSelect');
  const featureFieldSelect = document.getElementById('featureFieldSelect');
  if (pointAttachSelect) {
    pointAttachSelect.addEventListener('change', function(){
      // 同步到附属物字段下拉
      if (attachFieldSelect && pointAttachSelect.value) {
        attachFieldSelect.value = pointAttachSelect.value;
      }
      refreshPointAttachSettingsVisibility();
    });
  }
  if (attachFieldSelect) {
    attachFieldSelect.addEventListener('change', function(){
      refreshPointAttachSettingsVisibility();
    });
  }
  if (featureFieldSelect) {
    featureFieldSelect.addEventListener('change', function(){
      refreshPointFeatureSettingsVisibility();
    });
  }
  // 初始刷新（若自动映射已填充 pointAttach，这里也能立即展示表格）
  refreshPointAttachSettingsVisibility();
  refreshPointFeatureSettingsVisibility();
}

// 刷新特征设置区域显示
function refreshPointFeatureSettingsVisibility() {
  const section = document.getElementById('pointFeatureSettings');
  const pointFeatureSelect = document.getElementById('pointFeature');
  const featureFieldSelect = document.getElementById('featureFieldSelect');
  const chosenField = (featureFieldSelect && featureFieldSelect.value) || (pointFeatureSelect && pointFeatureSelect.value) || '';
  
  console.log('refreshPointFeatureSettingsVisibility called');
  console.log('featureFieldSelect:', featureFieldSelect);
  console.log('pointFeatureSelect:', pointFeatureSelect);
  console.log('chosenField:', chosenField);
  console.log('section:', section);
  
  if (section) {
    if (chosenField) {
      section.style.display = '';
      const fieldToLoad = featureFieldSelect && featureFieldSelect.value ? featureFieldSelect.value : (pointFeatureSelect ? pointFeatureSelect.value : '');
      console.log('fieldToLoad:', fieldToLoad);
      if (fieldToLoad) loadPointFeatureValues(fieldToLoad);
    } else {
      section.style.display = 'none';
    }
  }
}

// 添加字段选择变化监听器
function addFieldChangeListeners() {
  // 管线字段监听器
  const lineFieldIds = [
    'lineId','lineStart','lineEnd','lineStartElev','lineEndElev',
    'lineStartDepth','lineEndDepth','lineDiameter'
  ];
  
  lineFieldIds.forEach(id => {
    const sel = document.getElementById(id);
    if (sel) {
      sel.addEventListener('change', function() {
        updateMappingStatus(id);
        updateSubmitButtonState();
      });
    }
  });
  
  // 管点字段监听器
  const pointFieldIds = [
    'pointId','pointCode','pointAttach','pointAttachType','pointFeature','pointFeatureType','pointDirection','pointCoverType',
    'pointCoverRule','pointCoverMat','pointGround','pointDepth'
  ];
  
  pointFieldIds.forEach(id => {
    const sel = document.getElementById(id);
    if (sel) {
      sel.addEventListener('change', function() {
        updateMappingStatus(id);
        updateSubmitButtonState();
        // 如果是特征字段，需要刷新特征设置区域
        if (id === 'pointFeature') {
          refreshPointFeatureSettingsVisibility();
        }
      });
    }
  });
}

// 更新提交按钮状态
function updateSubmitButtonState() {
  const submitBtn = document.querySelector('.submit-btn');
  if (!submitBtn) return;
  
  const missingFields = getMissingFields();
  
  if (missingFields.length > 0) {
    submitBtn.disabled = true;
    submitBtn.title = `请先完成字段映射：${missingFields.join(', ')}`;
  } else {
    submitBtn.disabled = false;
    submitBtn.title = '点击提交建模任务';
  }
}

// 获取缺失的字段
function getMissingFields() {
  const missingFields = [];
  
  if (lineUploaded) {
    const lineRequiredFields = [
      { id: 'lineId', label: '唯一标识码' },
      { id: 'lineStart', label: '管段起点编号' },
      { id: 'lineEnd', label: '管段终点编号' },
      { id: 'lineStartElev', label: '起点管道高程' },
      { id: 'lineEndElev', label: '终点管道高程' },
      { id: 'lineStartDepth', label: '管段起点埋深' },
      { id: 'lineEndDepth', label: '管段终点埋深' },
      { id: 'lineDiameter', label: '管径' }
    ];
    
    lineRequiredFields.forEach(field => {
      const sel = document.getElementById(field.id);
      if (!sel.value) {
        missingFields.push(field.label);
      }
    });
  }
  
  if (pointUploaded) {
    const pointRequiredFields = [
      { id: 'pointId', label: '唯一标识码' },
      { id: 'pointCode', label: '管点编号' },
      { id: 'pointAttach', label: '附属物' },
      { id: 'pointFeature', label: '特征' },
      { id: 'pointCoverType', label: '井盖类型' },
      { id: 'pointCoverRule', label: '井盖规则' },
      { id: 'pointCoverMat', label: '井盖材质' },
      { id: 'pointGround', label: '地面高程' },
      { id: 'pointDepth', label: '井深' }
    ];
    
    pointRequiredFields.forEach(field => {
      const sel = document.getElementById(field.id);
      if (!sel.value) {
        missingFields.push(field.label);
      }
    });
  }
  
  return missingFields;
}

// 清空管线字段映射
function clearLineFields() {
  const lineFieldIds = [
    'lineId','lineStart','lineEnd','lineStartElev','lineEndElev',
    'lineStartDepth','lineEndDepth','lineDiameter'
  ];
  
  lineFieldIds.forEach(id => {
    const sel = document.getElementById(id);
    sel.innerHTML = '<option value="">请选择字段</option>';
    updateMappingStatus(id, 'manual');
  });
  
  lineFields = [];
  console.log('管线字段映射已清空');
  // 更新提交按钮状态
  updateSubmitButtonState();
}

// 清空管点字段映射
function clearPointFields() {
  const pointFieldIds = [
    'pointId','pointCode','pointAttach','pointAttachType','pointFeature','pointFeatureType','pointDirection','pointCoverType',
    'pointCoverRule','pointCoverMat','pointGround','pointDepth'
  ];
  
  pointFieldIds.forEach(id => {
    const sel = document.getElementById(id);
    sel.innerHTML = '<option value="">请选择字段</option>';
    updateMappingStatus(id, 'manual');
  });
  
  pointFields = [];
  console.log('管点字段映射已清空');
  // 更新提交按钮状态
  updateSubmitButtonState();
}

// 处理管线文件上传
function handleLineUpload() {
  if (!this.files[0]) return;
  console.log('选择了管线zip', this.files[0]);
  var formData = new FormData();
  formData.append('file', this.files[0]);
  console.log('准备上传管线zip');
  fetch('/api/uploadLine', { method: 'POST', body: formData })
    .then(res => res.json())
    .then(data => {
      let lineSel = document.getElementById('lineLayer');
      lineSel.innerHTML = '';
      data.layers.forEach(l => {
        let opt = document.createElement('option');
        opt.value = l; opt.text = l;
        lineSel.appendChild(opt);
      });
      fillSelectsWithMapping(data.fields, [
        'lineId','lineStart','lineEnd','lineStartElev','lineEndElev',
        'lineStartDepth','lineEndDepth','lineDiameter'
      ], 'line');
      lineUploaded = true; // 标记管线已上传
      console.log('管线文件上传成功，文件名保留');
      // 更新提交按钮状态
      updateSubmitButtonState();
    })
    .catch(error => {
      console.error('管线文件上传失败:', error);
      alert('管线文件上传失败，请重试');
    });
  // 不清空value，保持文件名显示
}

// 处理管点文件上传
function handlePointUpload() {
  if (!this.files[0]) return;
  console.log('选择了管点zip', this.files[0]);
  var formData = new FormData();
  formData.append('file', this.files[0]);
  console.log('准备上传管点zip');
  fetch('/api/uploadPoint', { method: 'POST', body: formData })
    .then(res => res.json())
    .then(data => {
      let pointSel = document.getElementById('pointLayer');
      pointSel.innerHTML = '';
      data.layers.forEach(l => {
        let opt = document.createElement('option');
        opt.value = l; opt.text = l;
        pointSel.appendChild(opt);
      });
      fillSelectsWithMapping(data.fields, [
        'pointId','pointCode','pointAttach','pointAttachType','pointFeature','pointFeatureType','pointDirection','pointCoverType',
        'pointCoverRule','pointCoverMat','pointGround','pointDepth'
      ], 'point');
      pointUploaded = true; // 标记管点已上传
      console.log('管点文件上传成功，文件名保留');
      // 更新提交按钮状态
      updateSubmitButtonState();
      // 刷新特征设置区域显示
      console.log('准备刷新特征设置区域...');
      refreshPointFeatureSettingsVisibility();
    })
    .catch(error => {
      console.error('管点文件上传失败:', error);
      alert('管点文件上传失败，请重试');
    });
  // 不清空value，保持文件名显示
}

function getInitials(str) {
  return str.split(/[_\s]+/).map(s => s[0]).join('').toLowerCase();
}

function findBestMatch(fieldName, availableFields) {
  if (!availableFields || availableFields.length === 0) return null;
  // 精确匹配
  for (let field of availableFields) {
    if (field.toLowerCase() === fieldName.toLowerCase()) {
      return field;
    }
  }
  // 包含匹配
  for (let field of availableFields) {
    if (field.toLowerCase().includes(fieldName.toLowerCase()) || 
        fieldName.toLowerCase().includes(field.toLowerCase())) {
      return field;
    }
  }
  // 模糊关键词匹配
  const keywords = fieldName.toLowerCase().split(/[_\s]+/);
  for (let field of availableFields) {
    const fieldLower = field.toLowerCase();
    let matchCount = 0;
    for (let keyword of keywords) {
      if (keyword.length > 2 && fieldLower.includes(keyword)) {
        matchCount++;
      }
    }
    if (matchCount >= keywords.length * 0.5) {
      return field;
    }
  }
  // 新增首字母匹配
  const fieldInitials = getInitials(fieldName);
  for (let field of availableFields) {
    if (getInitials(field) === fieldInitials) {
      return field;
    }
  }
  return null;
}

// 填充选择框并自动映射
function fillSelectsWithMapping(fields, fieldIds, type) {
  // 存储字段数据
  if (type === 'line') {
    lineFields = fields;
  } else {
    pointFields = fields;
  }
  
  fieldIds.forEach(id => {
    let sel = document.getElementById(id);
    sel.innerHTML = '<option value="">请选择字段</option>';
    
    fields.forEach(f => {
      let opt = document.createElement('option');
      opt.value = f;
      opt.text = f;
      sel.appendChild(opt);
    });
  });
  
  // 如果是管点，顺便填充附属物字段下拉（attach tab 用）
  if (type === 'point') {
    const attachFieldSelect = document.getElementById('attachFieldSelect');
    const attachTypeFieldSelect = document.getElementById('attachTypeFieldSelect');
    if (attachFieldSelect) {
      attachFieldSelect.innerHTML = '<option value="">请选择字段</option>';
      fields.forEach(f => {
        const opt = document.createElement('option');
        opt.value = f; opt.text = f;
        attachFieldSelect.appendChild(opt);
      });
      // 与 pointAttach 选择联动：若 pointAttach 已有值，则同步过去
      const pointAttachSel = document.getElementById('pointAttach');
      if (pointAttachSel && pointAttachSel.value) {
        attachFieldSelect.value = pointAttachSel.value;
      }
    }
    if (attachTypeFieldSelect) {
      attachTypeFieldSelect.innerHTML = '<option value="">请选择字段</option>';
      fields.forEach(f => {
        const opt = document.createElement('option');
        opt.value = f; opt.text = f;
        attachTypeFieldSelect.appendChild(opt);
      });
      const pointAttachTypeSel = document.getElementById('pointAttachType');
      if (pointAttachTypeSel && pointAttachTypeSel.value) {
        attachTypeFieldSelect.value = pointAttachTypeSel.value;
      }
    }
    const featureFieldSelect = document.getElementById('featureFieldSelect');
    const featureTypeFieldSelect = document.getElementById('featureTypeFieldSelect');
    if (featureFieldSelect) {
      featureFieldSelect.innerHTML = '<option value="">请选择字段</option>';
      fields.forEach(f => {
        const opt = document.createElement('option');
        opt.value = f; opt.text = f;
        featureFieldSelect.appendChild(opt);
      });
    }
    if (featureTypeFieldSelect) {
      featureTypeFieldSelect.innerHTML = '<option value="">请选择字段</option>';
      fields.forEach(f => {
        const opt = document.createElement('option');
        opt.value = f; opt.text = f;
        featureTypeFieldSelect.appendChild(opt);
      });
      const pointFeatureTypeSel = document.getElementById('pointFeatureType');
      if (pointFeatureTypeSel && pointFeatureTypeSel.value) {
        featureTypeFieldSelect.value = pointFeatureTypeSel.value;
      }
    }
  }
  
  // 执行自动映射
  autoMapFields(type);
  
  // 如果是管点，在自动映射后同步特征字段和附属物字段
  if (type === 'point') {
    console.log('开始同步特征字段...');
    const pointFeatureSel = document.getElementById('pointFeature');
    const featureFieldSelect = document.getElementById('featureFieldSelect');
    const pointFeatureTypeSel = document.getElementById('pointFeatureType');
    const featureTypeFieldSelect = document.getElementById('featureTypeFieldSelect');
    console.log('pointFeatureSel:', pointFeatureSel);
    console.log('pointFeatureSel.value:', pointFeatureSel ? pointFeatureSel.value : 'null');
    console.log('featureFieldSelect:', featureFieldSelect);
    
    if (pointFeatureSel && pointFeatureSel.value && featureFieldSelect) {
      featureFieldSelect.value = pointFeatureSel.value;
      console.log('特征字段同步完成，featureFieldSelect.value:', featureFieldSelect.value);
      // 同步后刷新特征设置区域显示
      refreshPointFeatureSettingsVisibility();
    } else {
      console.log('特征字段同步条件不满足');
    }
    if (pointFeatureTypeSel && pointFeatureTypeSel.value && featureTypeFieldSelect) {
      featureTypeFieldSelect.value = pointFeatureTypeSel.value;
    }
    
    // 同步附属物字段
    console.log('开始同步附属物字段...');
    const pointAttachSel = document.getElementById('pointAttach');
    const attachFieldSelect = document.getElementById('attachFieldSelect');
    const pointAttachTypeSel = document.getElementById('pointAttachType');
    const attachTypeFieldSelect = document.getElementById('attachTypeFieldSelect');
    
    if (pointAttachSel && pointAttachSel.value && attachFieldSelect) {
      attachFieldSelect.value = pointAttachSel.value;
      console.log('附属物字段同步完成，attachFieldSelect.value:', attachFieldSelect.value);
      // 同步后刷新附属物设置区域显示
      refreshPointAttachSettingsVisibility();
    }
    if (pointAttachTypeSel && pointAttachTypeSel.value && attachTypeFieldSelect) {
      attachTypeFieldSelect.value = pointAttachTypeSel.value;
      console.log('附属物类型字段同步完成，attachTypeFieldSelect.value:', attachTypeFieldSelect.value);
    }
  }
}

// 自动映射字段
function autoMapFields(type) {
  const fields = type === 'line' ? lineFields : pointFields;
  const mappings = fieldMappings[type];
  
  if (!fields || fields.length === 0) {
    console.log('没有可用字段进行映射');
    return;
  }
  
  Object.keys(mappings).forEach(fieldId => {
    const sel = document.getElementById(fieldId);
    const mappingKeywords = mappings[fieldId];
    
    // 尝试匹配
    let matchedField = null;
    for (let keyword of mappingKeywords) {
      matchedField = findBestMatch(keyword, fields);
      if (matchedField) break;
    }
    
    if (matchedField) {
      sel.value = matchedField;
      updateMappingStatus(fieldId, 'auto');
      if (fieldId === 'pointAttach') {
        console.log('触发pointAttach change事件');
        sel.dispatchEvent(new Event('change'));
      }
      if (fieldId === 'pointAttachType') {
        console.log('触发pointAttachType change事件');
        sel.dispatchEvent(new Event('change'));
      }
      if (fieldId === 'pointFeature') {
        console.log('触发pointFeature change事件，matchedField:', matchedField);
        sel.dispatchEvent(new Event('change'));
      }
      if (fieldId === 'pointFeatureType') {
        console.log('触发pointFeatureType change事件');
        sel.dispatchEvent(new Event('change'));
      }
    } else {
      sel.value = '';
      updateMappingStatus(fieldId, 'manual');
    }
  });
  
  console.log(`${type}字段自动映射完成`);
  // 更新提交按钮状态
  updateSubmitButtonState();
}

// 更新映射状态显示
function updateMappingStatus(fieldId, type) {
  const sel = document.getElementById(fieldId);
  const statusSpan = document.getElementById(fieldId + '-status');
  const fieldMapping = sel.closest('.field-mapping');
  
  if (!statusSpan) return;
  
  if (type === 'auto' || (sel.value && !type)) {
    // 检查是否是自动映射的
    const isAutoMapped = isFieldAutoMapped(fieldId, sel.value);
    if (isAutoMapped) {
      fieldMapping.className = 'field-mapping auto-mapped';
      statusSpan.textContent = '✅ 自动映射';
    } else {
      fieldMapping.className = 'field-mapping manual-select';
      statusSpan.textContent = '✏️ 手动选择';
    }
  } else {
    fieldMapping.className = 'field-mapping manual-select';
    statusSpan.textContent = '⚠️ 请选择字段';
  }
}

// 检查字段是否自动映射
function isFieldAutoMapped(fieldId, selectedValue) {
  const type = fieldId.startsWith('line') ? 'line' : 'point';
  const mappings = fieldMappings[type][fieldId];
  
  if (!mappings || !selectedValue) return false;
  
  for (let keyword of mappings) {
    if (findBestMatch(keyword, [selectedValue])) {
      return true;
    }
  }
  return false;
}

// 收集字段映射
function collectFieldMapping() {
  const line = {
    id: document.getElementById('lineId') ? document.getElementById('lineId').value : '',
    start: document.getElementById('lineStart') ? document.getElementById('lineStart').value : '',
    end: document.getElementById('lineEnd') ? document.getElementById('lineEnd').value : '',
    startElev: document.getElementById('lineStartElev') ? document.getElementById('lineStartElev').value : '',
    endElev: document.getElementById('lineEndElev') ? document.getElementById('lineEndElev').value : '',
    startDepth: document.getElementById('lineStartDepth') ? document.getElementById('lineStartDepth').value : '',
    endDepth: document.getElementById('lineEndDepth') ? document.getElementById('lineEndDepth').value : '',
    diameter: document.getElementById('lineDiameter') ? document.getElementById('lineDiameter').value : ''
  };
  const point = {
    id: document.getElementById('pointId') ? document.getElementById('pointId').value : '',
    code: document.getElementById('pointCode') ? document.getElementById('pointCode').value : '',
    attach: document.getElementById('pointAttach') ? document.getElementById('pointAttach').value : '',
    attachType: document.getElementById('pointAttachType') ? document.getElementById('pointAttachType').value : '',
    feature: document.getElementById('pointFeature') ? document.getElementById('pointFeature').value : '',
    featureType: document.getElementById('pointFeatureType') ? document.getElementById('pointFeatureType').value : '',
    direction: document.getElementById('pointDirection') ? document.getElementById('pointDirection').value : '',
    coverType: document.getElementById('pointCoverType') ? document.getElementById('pointCoverType').value : '',
    coverRule: document.getElementById('pointCoverRule') ? document.getElementById('pointCoverRule').value : '',
    coverMat: document.getElementById('pointCoverMat') ? document.getElementById('pointCoverMat').value : '',
    ground: document.getElementById('pointGround') ? document.getElementById('pointGround').value : '',
    depth: document.getElementById('pointDepth') ? document.getElementById('pointDepth').value : ''
  };
  
  return { lineFields: line, pointFields: point };
}

// 提交所有数据
// 全局变量，用于防止重复提交
let isSubmitting = false;

function submitAll() {
  // 防止重复点击
  if (isSubmitting) {
    console.log('建模任务正在处理中，请勿重复提交');
    return;
  }
  
  // 设置提交状态
  isSubmitting = true;
  
  // 更新按钮状态
  const submitBtn = document.querySelector('.submit-btn');
  if (submitBtn) {
    submitBtn.disabled = true;
    submitBtn.textContent = '建模中...';
    submitBtn.classList.add('submitting');
  }
  
  // 隐藏建模完成后的提示、下载、预览信息
  document.getElementById('result').innerHTML = '';
  const downloadSection = document.getElementById('downloadSection');
  if (downloadSection) downloadSection.style.display = 'none';
  
  const missingFields = [];
  
  // 分别校验管线和管点字段
  if (lineUploaded) {
    // 如果上传了管线文件，校验所有管线字段
    const lineRequiredFields = [
      { id: 'lineId', label: '唯一标识码' },
      { id: 'lineStart', label: '管段起点编号' },
      { id: 'lineEnd', label: '管段终点编号' },
      { id: 'lineStartElev', label: '起点管道高程' },
      { id: 'lineEndElev', label: '终点管道高程' },
      { id: 'lineStartDepth', label: '管段起点埋深' },
      { id: 'lineEndDepth', label: '管段终点埋深' },
      { id: 'lineDiameter', label: '管径' }
    ];
    
    lineRequiredFields.forEach(field => {
      const sel = document.getElementById(field.id);
      if (!sel.value) {
        missingFields.push(`管线-${field.label}`);
      }
    });
  }
  
  if (pointUploaded) {
    // 如果上传了管点文件，校验所有管点字段
    const pointRequiredFields = [
      { id: 'pointId', label: '唯一标识码' },
      { id: 'pointCode', label: '管点编号' },
      { id: 'pointAttach', label: '附属物' },
      { id: 'pointFeature', label: '特征' },
      { id: 'pointCoverType', label: '井盖类型' },
      { id: 'pointCoverRule', label: '井盖规格' },
      { id: 'pointCoverMat', label: '井盖材质' },
      { id: 'pointGround', label: '地面高程' },
      { id: 'pointDepth', label: '井深' }
    ];
    pointRequiredFields.forEach(field => {
      const sel = document.getElementById(field.id);
      if (!sel.value) {
        missingFields.push(`管点-${field.label}`);
      }
    });
  }
  
  if (missingFields.length > 0) {
    alert('以下字段未完整配置：\n' + missingFields.join('\n'));
    // 重置提交状态
    isSubmitting = false;
    if (submitBtn) {
      submitBtn.disabled = false;
      submitBtn.textContent = '提交建模任务';
      submitBtn.classList.remove('submitting');
    }
    return;
  }
  
  // 获取分片大小
  const gridSizeSelect = document.getElementById('gridSize');
  const gridSizeValue = gridSizeSelect ? gridSizeSelect.value : '0.5';
  const gridSize = parseFloat(gridSizeValue);
  
  const modelSettings = {
    renderScheme: document.getElementById('renderScheme').value,
    pipeColor: document.getElementById('pipeColor').value,
    pipeTexture: uploadedTextureFileName,
    gridSize: gridSize,
    enableDraco: document.getElementById('enableDraco').checked,
    depthBase: (document.getElementById('depthBase') && document.getElementById('depthBase').value) || 'bottom',
    attachField: (document.getElementById('attachFieldSelect') && document.getElementById('attachFieldSelect').value) || (document.getElementById('pointAttach') && document.getElementById('pointAttach').value) || '',
    attachTypeField: (document.getElementById('attachTypeFieldSelect') && document.getElementById('attachTypeFieldSelect').value) || (document.getElementById('pointAttachType') && document.getElementById('pointAttachType').value) || '',
    // 附属物一对多：按 附属物值 -> { 附属物类型值: 模型路径 }；类型未选则使用键 'default'
    attachModels: (function(){
      const map = {};
      const rows = document.querySelectorAll('#pointAttachTable tbody tr');
      rows.forEach(function(tr){
        const cols = tr.querySelectorAll('td');
        const attachVal = cols[0] ? cols[0].textContent.trim() : '';
        if (!attachVal) return;
        
        // 收集该附属物值对应的所有已上传的变体模型
        const variants = {};
        for (let key in tr.dataset) {
          if (key.startsWith('atype_')) {
            const variantName = key.substring(6); // 移除 'atype_' 前缀
            const modelPath = tr.dataset[key];
            if (modelPath) {
              variants[variantName] = modelPath;
            }
          }
        }
        
        // 如果有任何变体模型，添加到结果中
        if (Object.keys(variants).length > 0) {
          map[attachVal] = variants;
          console.log(`附属物值 "${attachVal}" 的变体模型:`, variants);
        }
      });
      return map;
    })(),
    featureField: (document.getElementById('featureFieldSelect') && document.getElementById('featureFieldSelect').value) || (document.getElementById('pointFeature') && document.getElementById('pointFeature').value) || '',
    featureTypeField: (document.getElementById('featureTypeFieldSelect') && document.getElementById('featureTypeFieldSelect').value) || (document.getElementById('pointFeatureType') && document.getElementById('pointFeatureType').value) || '',
    // 特征一对多：按 特征值 -> { 特征类型值: 模型路径 }；类型未选则使用键 'default'
    featureModels: (function(){
      const map = {};
      const rows = document.querySelectorAll('#pointFeatureTable tbody tr.feature-main-row');
      rows.forEach(function(tr){
        const featureVal = tr.dataset.featureValue;
        if (!featureVal) return;
        
        // 收集该特征值对应的所有已上传的变体模型
        const variants = {};
        for (let key in tr.dataset) {
          if (key.startsWith('variant_')) {
            const variantName = key.substring(8); // 移除 'variant_' 前缀
            const modelPath = tr.dataset[key];
            if (modelPath) {
              variants[variantName] = modelPath;
            }
          }
        }
        
        // 如果有任何变体模型，添加到结果中
        if (Object.keys(variants).length > 0) {
          map[featureVal] = variants;
          console.log(`特征值 "${featureVal}" 的变体模型:`, variants);
        }
      });
      return map;
    })()
  };
  console.log('提交建模参数:', modelSettings);
  const fm = collectFieldMapping();
  const data = {
    lineLayer: lineUploaded ? document.getElementById('lineLayer').value : '',
    pointLayer: pointUploaded ? document.getElementById('pointLayer').value : '',
    lineFields: fm.lineFields,
    pointFields: fm.pointFields,
    modelSettings: modelSettings
  };
  
  fetch('/api/model/generate', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(data)
  })
  .then(res => res.text())
  .then(text => {
    let resp;
    try { resp = JSON.parse(text); } catch(e) { throw new Error(text); }
    if (resp && resp.success === false) throw new Error(resp.message || '建模失败');
    showDownloadSection();
    document.getElementById('result').innerHTML = '🎉 <strong>建模完成！</strong>';
  })
  .catch(err => {
    alert('建模失败: ' + (err.message || err));
    document.getElementById('result').innerHTML = '❌ 建模失败: ' + (err.message || err);
  })
  .finally(() => {
    isSubmitting = false;
    if (submitBtn) {
      submitBtn.disabled = false;
      submitBtn.textContent = '提交建模任务';
      submitBtn.classList.remove('submitting');
    }
  });
}

// 显示下载区域
function showDownloadSection() {
  console.log('尝试显示下载区域...');
  const downloadSection = document.getElementById('downloadSection');
  console.log('找到下载区域元素:', downloadSection);
  
  if (downloadSection) {
    downloadSection.style.display = 'block';
    console.log('下载区域已显示');
  } else {
    console.error('未找到downloadSection元素');
  }
}

function loadPointAttachValues(fieldName) {
  fetch('/api/point/attach-values?field=' + encodeURIComponent(fieldName))
    .then(r => r.json())
    .then(data => {
      if (!data.success) throw new Error(data.message || '加载附属物属性值失败');
      buildPointAttachTable(data.values || []);
    })
    .catch(err => {
      console.error('加载附属物属性值失败:', err);
      buildPointAttachTable([]);
    });
}
function loadPointFeatureValues(fieldName) {
  console.log('loadPointFeatureValues called with fieldName:', fieldName);
  fetch('/api/point/feature-values?field=' + encodeURIComponent(fieldName))
    .then(r => r.json())
    .then(data => {
      console.log('loadPointFeatureValues response:', data);
      if (!data.success) throw new Error(data.message || '加载特征属性值失败');
      buildPointFeatureTable(data.values || []);
    })
    .catch(err => {
      console.error('加载特征属性值失败:', err);
      buildPointFeatureTable([]);
    });
}

// 构建附属物表格（每行上传ZIP包）
function buildPointAttachTable(values) {
  const tbody = document.querySelector('#pointAttachTable tbody');
  tbody.innerHTML = '';
  values.forEach(v => {
    const tr = document.createElement('tr');
    const tdVal = document.createElement('td');
    const tdVariant = document.createElement('td');
    const tdCtrl = document.createElement('td');
    const tdStatus = document.createElement('td');
    tdVal.style.padding = '6px 4px';
    tdVariant.style.padding = '6px 4px';
    tdCtrl.style.padding = '6px 4px';
    tdStatus.style.padding = '6px 4px';

    tdVal.textContent = v;

    // 附属物类型下拉（从后端按类型字段取值）
    const attachTypeSel = document.createElement('select');
    attachTypeSel.className = 'attach-type-variant';
    attachTypeSel.innerHTML = '<option value="">选择类型</option>';
    // 默认模型选项
    (function(){
      const opt = document.createElement('option');
      opt.value = 'default';
      opt.text = 'default(默认)';
      opt.selected = true; // 默认选中
      attachTypeSel.appendChild(opt);
    })();
    tdVariant.appendChild(attachTypeSel);

    const attachTypeField = (document.getElementById('attachTypeFieldSelect') && document.getElementById('attachTypeFieldSelect').value) || (document.getElementById('pointAttachType') && document.getElementById('pointAttachType').value) || '';
    const attachField = (document.getElementById('attachFieldSelect') && document.getElementById('attachFieldSelect').value) || (document.getElementById('pointAttach') && document.getElementById('pointAttach').value) || '';
    if (!attachTypeField) {
      // 未完成“附属物类型字段”映射：禁用类型选择与上传
      attachTypeSel.disabled = true;
      attachTypeSel.title = '请先在字段映射中选择“附属物类型”字段';
    }
    if (attachTypeField && attachField && v) {
      fetch('/api/point/distinct-values-by?field=' + encodeURIComponent(attachTypeField) + '&byField=' + encodeURIComponent(attachField) + '&byValue=' + encodeURIComponent(v))
        .then(r => r.json())
        .then(d => {
          if (d.success && Array.isArray(d.values)) {
            d.values.forEach(val => {
              const opt = document.createElement('option');
              opt.value = val; opt.text = val;
              attachTypeSel.appendChild(opt);
            });
          }
        })
        .catch(() => {});
    }

    const fileInput = document.createElement('input');
    fileInput.type = 'file';
    fileInput.accept = '.zip';
    // 初始禁用：必须先选择附属物类型（可选 default）；若映射未完成则始终禁用
    fileInput.disabled = true;
    fileInput.title = attachTypeField ? '请先选择附属物类型（可选 default）' : '请先在字段映射中选择“附属物类型”字段';

    const nameSpan = document.createElement('span');
    nameSpan.style.marginLeft = '8px';
    nameSpan.style.fontSize = '12px';
    nameSpan.style.color = '#888';

    // 类型选择联动：有选择才允许上传
    attachTypeSel.addEventListener('change', function(){
      const selected = this.value;
      if (selected && attachTypeField) {
        fileInput.disabled = false;
        // 如果该类型已有上传记录，则显示状态
        const existing = tr.dataset[`atype_${selected}`];
        if (existing) {
          nameSpan.textContent = existing.split('/').pop();
          tdStatus.textContent = '✅ 已上传';
          tdStatus.style.color = '#28a745';
        } else {
          nameSpan.textContent = '';
          tdStatus.textContent = '';
        }
      } else {
        fileInput.disabled = true;
        fileInput.value = '';
        nameSpan.textContent = '';
        tdStatus.textContent = '';
      }
    });
    
    // 触发初始change事件，确保默认选中的'default'选项生效
    if (attachTypeField) {
      attachTypeSel.dispatchEvent(new Event('change'));
    }

    fileInput.addEventListener('change', function() {
      if (!fileInput.files || !fileInput.files[0]) { 
        nameSpan.textContent=''; 
        tdStatus.textContent = '';
        delete tr.dataset.accessory; 
        return; 
      }
      const f = fileInput.files[0];
      if (!/\.zip$/i.test(f.name)) { 
        alert('仅支持ZIP文件，请上传包含OBJ、MTL和贴图文件的ZIP包'); 
        fileInput.value=''; 
        nameSpan.textContent=''; 
        tdStatus.textContent = '';
        delete tr.dataset.accessory; 
        return; 
      }
      const attachTypeVal = attachTypeSel.value;
      if (!attachTypeVal) {
        alert('请先选择附属物类型（可选 default）');
        fileInput.value='';
        return;
      }
      // 显示上传状态
      tdStatus.textContent = '⏳ 上传中...';
      tdStatus.style.color = '#ffa500';
      
      const form = new FormData();
      form.append('file', f);
      form.append('attach', v);
      form.append('attachType', attachTypeVal);
      
      fetch('/api/uploadAccessory', { method: 'POST', body: form })
        .then(r => r.json())
        .then(data => {
          if (!data.success) throw new Error(data.message || '上传失败');
          // 记录到按类型的键，便于行内和提交阶段读取
          tr.dataset[`atype_${attachTypeVal}`] = data.modelPath;
          nameSpan.textContent = f.name;
          tdStatus.textContent = '✅ 已上传';
          tdStatus.style.color = '#28a745';
        })
        .catch(err => {
          alert('上传失败: ' + (err.message || err));
          fileInput.value = '';
          nameSpan.textContent = '';
          tdStatus.textContent = '❌ 上传失败';
          tdStatus.style.color = '#dc3545';
          delete tr.dataset.accessory;
        });
    });

    tdCtrl.appendChild(fileInput);
    tdCtrl.appendChild(nameSpan);

    tr.appendChild(tdVal);
    tr.appendChild(tdVariant);
    tr.appendChild(tdCtrl);
    tr.appendChild(tdStatus);
    tbody.appendChild(tr);
  });
}
function buildPointFeatureTable(values) {
  console.log('buildPointFeatureTable called with values:', values);
  const tbody = document.querySelector('#pointFeatureTable tbody');
  if (!tbody) {
    console.error('pointFeatureTable tbody not found');
    return;
  }
  tbody.innerHTML = '';
  values.forEach(v => {
    // 为每个特征值创建一个主行
    const mainRow = createFeatureMainRow(v);
    tbody.appendChild(mainRow);
  });
}

function createFeatureMainRow(featureValue) {
  const tr = document.createElement('tr');
  tr.className = 'feature-main-row';
  tr.dataset.featureValue = featureValue;
  
  // 属性值列
  const tdVal = document.createElement('td');
  tdVal.textContent = featureValue;
  tdVal.style.padding = '8px 4px';
  tdVal.style.borderBottom = '1px solid #eee';
  tdVal.style.fontWeight = 'bold';
  
  // 特征类型列
  const tdVariant = document.createElement('td');
  tdVariant.style.padding = '8px 4px';
  tdVariant.style.borderBottom = '1px solid #eee';
  const variantSelect = document.createElement('select');
  variantSelect.className = 'feature-type-variant';
  variantSelect.style.width = '120px';
  variantSelect.style.fontSize = '12px';
  variantSelect.innerHTML = '<option value="">选择特征类型</option>';
  // 默认模型选项
  (function(){
    const opt = document.createElement('option');
    opt.value = 'default';
    opt.text = 'default(默认)';
    opt.selected = true; // 默认选中
    variantSelect.appendChild(opt);
  })();
  tdVariant.appendChild(variantSelect);
  
  // 特征模型列
  const tdCtrl = document.createElement('td');
  tdCtrl.style.padding = '8px 4px';
  tdCtrl.style.borderBottom = '1px solid #eee';
  const fileInput = document.createElement('input');
  fileInput.type = 'file';
  fileInput.accept = '.zip';
  fileInput.style.width = '200px';
  fileInput.style.fontSize = '12px';
  fileInput.disabled = true;
  
  const nameSpan = document.createElement('span');
  nameSpan.style.marginLeft = '8px';
  nameSpan.style.fontSize = '12px';
  nameSpan.style.color = '#888';
  
  // 状态列
  const tdStatus = document.createElement('td');
  tdStatus.style.padding = '8px 4px';
  tdStatus.style.borderBottom = '1px solid #eee';
  tdStatus.style.fontSize = '12px';
  
  // 加载特征类型下拉（来自选定的特征类型字段）
  const featureTypeField = (document.getElementById('featureTypeFieldSelect') && document.getElementById('featureTypeFieldSelect').value) || (document.getElementById('pointFeatureType') && document.getElementById('pointFeatureType').value) || '';
  const featureField = (document.getElementById('featureFieldSelect') && document.getElementById('featureFieldSelect').value) || (document.getElementById('pointFeature') && document.getElementById('pointFeature').value) || '';
  if (featureTypeField && featureField && featureValue) {
    fetch('/api/point/distinct-values-by?field=' + encodeURIComponent(featureTypeField) + '&byField=' + encodeURIComponent(featureField) + '&byValue=' + encodeURIComponent(featureValue))
      .then(r => r.json())
      .then(d => {
        if (d.success && Array.isArray(d.values)) {
          d.values.forEach(val => {
            const opt = document.createElement('option');
            opt.value = val; opt.text = val;
            variantSelect.appendChild(opt);
          });
        }
      })
      .catch(() => {});
  }
  
  // 变体选择事件
  variantSelect.addEventListener('change', function() {
    const selectedVariant = this.value;
    if (selectedVariant) {
      fileInput.disabled = false;
      // 检查是否已有该变体的模型
      const existingModel = tr.dataset[`variant_${selectedVariant}`];
      if (existingModel) {
        nameSpan.textContent = existingModel;
        tdStatus.textContent = '✅ 已上传';
        tdStatus.style.color = '#28a745';
      } else {
        nameSpan.textContent = '';
        tdStatus.textContent = '';
      }
    } else {
      fileInput.disabled = true;
      nameSpan.textContent = '';
      tdStatus.textContent = '';
    }
  });
  
  // 触发初始change事件，确保默认选中的'default'选项生效
  variantSelect.dispatchEvent(new Event('change'));
  
  // 文件上传事件
  fileInput.addEventListener('change', function() {
    if (!fileInput.files || !fileInput.files[0]) { 
      nameSpan.textContent = ''; 
      tdStatus.textContent = '';
      const selectedVariant = variantSelect.value;
      if (selectedVariant) {
        delete tr.dataset[`variant_${selectedVariant}`];
      }
      return; 
    }
    const f = fileInput.files[0];
    if (!/\.zip$/i.test(f.name)) { 
      alert('仅支持ZIP文件，请上传包含OBJ、MTL和贴图文件的ZIP包'); 
      fileInput.value = ''; 
      nameSpan.textContent = ''; 
      tdStatus.textContent = '';
      return; 
    }
    
    const selectedVariant = variantSelect.value;
    if (!selectedVariant) {
      alert('请先选择模型变体');
      fileInput.value = '';
      return;
    }
    
    // 显示上传状态
    tdStatus.textContent = '⏳ 上传中...';
    tdStatus.style.color = '#ffa500';
    
    const form = new FormData();
    form.append('file', f);
    form.append('field', (document.getElementById('featureFieldSelect') && document.getElementById('featureFieldSelect').value) || '');
    form.append('value', featureValue);
    form.append('variant', selectedVariant);
    
    fetch('/api/uploadFeature', { method: 'POST', body: form })
      .then(r => r.json())
      .then(data => {
        if (!data.success) throw new Error(data.message || '上传失败');
        tr.dataset[`variant_${selectedVariant}`] = data.modelPath;
        nameSpan.textContent = f.name;
        tdStatus.textContent = '✅ 已上传';
        tdStatus.style.color = '#28a745';
      })
      .catch(err => {
        alert('上传失败: ' + (err.message || err));
        fileInput.value = '';
        nameSpan.textContent = '';
        tdStatus.textContent = '❌ 上传失败';
        tdStatus.style.color = '#dc3545';
        delete tr.dataset[`variant_${selectedVariant}`];
      });
  });
  
  tdCtrl.appendChild(fileInput);
  tdCtrl.appendChild(nameSpan);
  
  tr.appendChild(tdVal);
  tr.appendChild(tdVariant);
  tr.appendChild(tdCtrl);
  tr.appendChild(tdStatus);
  
  return tr;
}


// 下载完整3DTiles包
function download3DTilesZip() {
  console.log('开始下载3DTiles ZIP包...');
  
  // 显示下载状态
  const downloadBtn = document.querySelector('.download-btn');
  const originalText = downloadBtn.textContent;
  downloadBtn.textContent = '⏳ 检查文件状态...';
  downloadBtn.disabled = true;
  
  // 先检查文件状态
  fetch('/api/download/3dtiles/status')
    .then(response => response.json())
    .then(data => {
      console.log('文件状态检查结果:', data);
      
      if (!data.success) {
        throw new Error(data.message || '检查文件状态失败');
      }
      
      if (!data.hasFiles) {
        throw new Error(data.message || '未找到3DTiles文件，请先完成建模任务');
      }
      
      // 文件存在，开始下载
      downloadBtn.textContent = '⏳ 正在下载...';
      return fetch('/api/download/3dtiles/zip');
    })
    .then(response => {
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }
      return response.blob();
    })
    .then(blob => {
      // 创建下载链接
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = '3dtiles.zip';
      link.style.display = 'none';
      
      // 添加到DOM并触发下载
      document.body.appendChild(link);
      link.click();
      
      // 清理
      document.body.removeChild(link);
      window.URL.revokeObjectURL(url);
      
      console.log('ZIP下载成功');
      
      // 恢复按钮状态
      downloadBtn.textContent = originalText;
      downloadBtn.disabled = false;
      
      // 显示成功消息
      alert('✅ 3DTiles包下载成功！');
    })
    .catch(error => {
      console.error('下载失败:', error);
      
      // 恢复按钮状态
      downloadBtn.textContent = originalText;
      downloadBtn.disabled = false;
      
      // 显示错误消息
      alert(`❌ 下载失败: ${error.message}\n\n请检查：\n1. 是否已完成建模任务\n2. 网络连接是否正常\n3. 浏览器是否允许下载`);
    });
} 