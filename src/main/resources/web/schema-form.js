/**
 * Schema-driven form builder for tree configuration.
 * Generates HTML forms dynamically from JSON schema and config values.
 */

class SchemaFormBuilder {
    constructor(schema) {
        this.schema = schema;
    }

    /**
     * Build a complete form from schema and config data
     */
    buildForm(config, containerElement) {
        containerElement.innerHTML = ''; // Clear previous content

        const properties = this.schema.properties || {};

        for (const [key, definition] of Object.entries(properties)) {
            const value = config ? config[key] : undefined;
            const field = this.createField(key, definition, value);
            if (field) {
                containerElement.appendChild(field);
            }
        }
    }

    /**
     * Create a form field based on schema definition
     */
    createField(key, definition, value) {
        const wrapper = document.createElement('div');
        wrapper.className = 'form-field';
        wrapper.dataset.fieldKey = key;

        const label = document.createElement('label');
        label.textContent = this.toDisplayName(key);
        if (definition.description) {
            label.title = definition.description;
        }
        wrapper.appendChild(label);

        let input;
        const type = definition.type;

        if (definition.enum) {
            // Dropdown for enum values
            input = this.createSelect(definition.enum, value || definition.default);
        } else if (type === 'boolean') {
            input = this.createCheckbox(value !== undefined ? value : definition.default);
        } else if (type === 'integer' || type === 'number') {
            input = this.createNumber(definition, value);
        } else if (type === 'string') {
            input = this.createText(value || definition.default || '');
        } else if (type === 'object') {
            input = this.createObjectField(key, definition, value);
        } else if (type === 'array') {
            input = this.createArrayField(key, definition, value);
        } else {
            // Fallback: raw JSON textarea
            input = this.createJsonTextarea(value);
        }

        input.dataset.configKey = key;
        wrapper.appendChild(input);

        return wrapper;
    }

    createSelect(options, selectedValue) {
        const select = document.createElement('select');
        select.className = 'config-select';

        options.forEach(opt => {
            const option = document.createElement('option');
            option.value = opt;
            option.textContent = this.toDisplayName(opt.replace('minecraft:', ''));
            if (opt === selectedValue) {
                option.selected = true;
            }
            select.appendChild(option);
        });

        return select;
    }

    createCheckbox(checked) {
        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.className = 'config-checkbox';
        checkbox.checked = checked || false;
        return checkbox;
    }

    createNumber(definition, value) {
        const input = document.createElement('input');
        input.type = 'number';
        input.className = 'config-number';
        input.value = value !== undefined ? value : (definition.default || 0);

        if (definition.minimum !== undefined) input.min = definition.minimum;
        if (definition.maximum !== undefined) input.max = definition.maximum;

        return input;
    }

    createText(value) {
        const input = document.createElement('input');
        input.type = 'text';
        input.className = 'config-text';
        input.value = value || '';
        return input;
    }

    createObjectField(key, definition, value) {
        const fieldset = document.createElement('fieldset');
        fieldset.className = 'config-object';

        const legend = document.createElement('legend');
        legend.textContent = this.toDisplayName(key);
        fieldset.appendChild(legend);

        const properties = definition.properties || {};

        for (const [subKey, subDef] of Object.entries(properties)) {
            const subValue = value ? value[subKey] : undefined;
            const field = this.createField(subKey, subDef, subValue);
            if (field) {
                fieldset.appendChild(field);
            }
        }

        return fieldset;
    }

    createArrayField(key, definition, value) {
        const container = document.createElement('div');
        container.className = 'config-array';

        const header = document.createElement('div');
        header.className = 'array-header';
        header.textContent = this.toDisplayName(key);
        container.appendChild(header);

        const itemsList = document.createElement('div');
        itemsList.className = 'array-items';
        container.appendChild(itemsList);

        const items = value || definition.default || [];
        items.forEach((item, index) => {
            const itemEl = this.createArrayItem(definition.items, item, index);
            itemsList.appendChild(itemEl);
        });

        const addBtn = document.createElement('button');
        addBtn.type = 'button';
        addBtn.className = 'btn-add-item';
        addBtn.textContent = '+ Add Item';
        addBtn.onclick = () => {
            const newItem = this.createArrayItem(definition.items, {}, itemsList.children.length);
            itemsList.appendChild(newItem);
        };
        container.appendChild(addBtn);

        return container;
    }

    createArrayItem(itemSchema, value, index) {
        const wrapper = document.createElement('div');
        wrapper.className = 'array-item';
        wrapper.dataset.itemIndex = index;

        const content = document.createElement('div');
        content.className = 'item-content';

        if (itemSchema && itemSchema.properties) {
            for (const [key, def] of Object.entries(itemSchema.properties)) {
                const val = value ? value[key] : undefined;
                const field = this.createField(key, def, val);
                if (field) content.appendChild(field);
            }
        } else {
            const input = this.createJsonTextarea(value);
            content.appendChild(input);
        }

        wrapper.appendChild(content);

        const deleteBtn = document.createElement('button');
        deleteBtn.type = 'button';
        deleteBtn.className = 'btn-delete-item';
        deleteBtn.textContent = '×';
        deleteBtn.onclick = () => wrapper.remove();
        wrapper.appendChild(deleteBtn);

        return wrapper;
    }

    createJsonTextarea(value) {
        const textarea = document.createElement('textarea');
        textarea.className = 'config-json';
        textarea.rows = 5;
        textarea.value = value !== undefined ? JSON.stringify(value, null, 2) : '';
        textarea.dataset.isRawJson = 'true';
        return textarea;
    }

    toDisplayName(str) {
        return str
            .replace(/_/g, ' ')
            .replace(/\b\w/g, c => c.toUpperCase());
    }
}

window.SchemaFormBuilder = SchemaFormBuilder;
